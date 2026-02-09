# Sistema de Paginação Kafka - Instruções para Implementação

## Contexto do Problema

Você precisa implementar um sistema que resolve o seguinte desafio:

**Problema Principal:** Mensagens Kafka têm limite de 1MB, mas agregados de dados podem ter 3MB, 5MB ou mais.

**Solução:** Sistema de paginação com dois componentes independentes:
1. **API Pagination** - Recebe dados grandes, divide em páginas <1MB, armazena no changelog
2. **API Consumer** - Recebe notificação (summary), busca páginas específicas, reconstrói dados completos

**Restrições Críticas:**
- Não pode enviar mensagens >1MB no Kafka
- Tratamento genérico: não deserializar key/value (podem ter serializadores customizados)
- Múltiplas paginações podem acontecer simultaneamente (concorrência)
- Consumer deve buscar apenas páginas específicas, não ler changelog inteiro

---

## Arquitetura - Visão Geral

### Fluxo de Dados

```
[Dados grandes] → API Pagination
                       ↓
                  Divide em páginas
                       ↓
                  ┌────────────────────┐
                  │ changelog-paginas  │ ← Armazena páginas (chave = TRANSACTION_ID)
                  └────────────────────┘
                       ↓
                  Ao terminar paginação
                       ↓
                  ┌────────────────────┐
                  │  topic-summary     │ ← Notifica conclusão + lista de IDs
                  └────────────────────┘
                       ↓
                  API Consumer recebe
                       ↓
                  Busca páginas específicas no changelog
                       ↓
                  Agrega dados completos
                       ↓
                  [Processa]
```

### Tópicos Kafka

**changelog-paginas:**
- **Propósito:** Armazenar páginas individuais
- **Chave:** `TRANSACTION_ID` (String, ID único da página)
- **Valor:** Payload da página (genérico, não deserializar)
- **Retenção:** Por tamanho (ex: 1GB por partição)
- **Compaction:** `cleanup.policy=compact` (mantém última versão de cada chave)
- **Compressão:** `lz4` (melhor relação velocidade/compressão)

**topic-summary:**
- **Propósito:** Notificar conclusão da paginação
- **Chave:** `PAGE_STREAM_ID` (String, ID da paginação)
- **Valor:** SummaryDTO (JSON)
- **Retenção:** Por tempo (ex: 24 horas)
- **Compaction:** `cleanup.policy=delete`

---

## Headers Kafka - Especificação

### Headers Obrigatórios (Páginas no changelog)

Toda mensagem publicada no `changelog-paginas` DEVE conter:

| Header | Tipo | Descrição | Quando Presente | Validação |
|--------|------|-----------|-----------------|-----------|
| `PAGE_STREAM_ID` | String | ID único que identifica toda a paginação | Sempre | **OBRIGATÓRIO** - Se ausente, ignorar mensagem |
| `TRANSACTION_ID` | String | ID único desta página específica | Sempre | **OBRIGATÓRIO** - Usado como chave Kafka |
| `ELEMENT_COUNT` | Integer | Quantidade de itens nesta página | Sempre | **OBRIGATÓRIO** - Validar > 0 |
| `PAGE_NUMBER` | Integer | Número sequencial da página (1, 2, 3...) | Sempre | **OBRIGATÓRIO** - Usado para ordenação |
| `TOTAL_ELEMENT_COUNT` | Integer | Total de elementos em TODA a paginação | **Apenas última página** | Indica fim da paginação |
| `MESSAGE_SOURCE` | String | Identificador de quem criou a paginação | Sempre | Rastreabilidade |
| `SENDING_TIME` | Long | Timestamp epoch (ms) de quando foi enviado | Sempre | Rastreabilidade |
| `ORIGINAL_KEY` | byte[] | Chave original da mensagem (serializada) | Sempre | Preserva chave sem deserializar |
| `ORIGINAL_KEY_SERIALIZER` | String | Nome do serializer da chave original | Opcional | Para debug/reconstrução |

### Por que cada header existe?

- **PAGE_STREAM_ID:** Identifica qual paginação esta página pertence (isolamento entre paginações concorrentes)
- **TRANSACTION_ID:** Índice único para buscar esta página específica no changelog
- **ELEMENT_COUNT:** Saber quantos itens tem nesta página (validação)
- **PAGE_NUMBER:** Ordenar páginas corretamente antes de agregar
- **TOTAL_ELEMENT_COUNT:** Detectar que paginação terminou + validar totalização
- **MESSAGE_SOURCE:** Rastreabilidade, debug
- **SENDING_TIME:** Rastreabilidade, ordenação temporal se necessário
- **ORIGINAL_KEY:** Preservar chave original sem deserializar (tratamento genérico)

---

## Princípios de Design - LEIA COM ATENÇÃO

### 1. Genericidade Absoluta

**NUNCA deserialize o payload (key/value) das mensagens originais.**

Razão: Serializadores podem ser customizados (Avro, Protobuf, custom). Seu sistema é infraestrutura, não conhece o domínio.

```
✅ CORRETO: Manipular apenas headers e metadados
✅ CORRETO: Passar payload como byte[] ou Object genérico
❌ ERRADO: Fazer cast para tipos específicos
❌ ERRADO: Deserializar para inspecionar conteúdo
```

### 2. Chave Kafka vs Chave Original

**Chave Kafka = TRANSACTION_ID** (índice técnico, String)
- Usado para indexação no Kafka
- Permite busca rápida
- Funciona com compactação

**Chave Original = ORIGINAL_KEY header** (preservada como byte[])
- Não toca no formato original
- Pode ser reconstruída depois se necessário

### 3. Detecção de Fim de Paginação

**Última página é identificada por:** Header `TOTAL_ELEMENT_COUNT` presente

```
Página 1: ELEMENT_COUNT=500, TOTAL_ELEMENT_COUNT=ausente
Página 2: ELEMENT_COUNT=500, TOTAL_ELEMENT_COUNT=ausente
Página 3: ELEMENT_COUNT=200, TOTAL_ELEMENT_COUNT=1200 ← ÚLTIMA
```

**AÇÃO quando detectar última página:**
1. Publicar todas as páginas no changelog
2. Criar summary com lista de TRANSACTION_IDs
3. Publicar summary no topic-summary

### 4. Isolamento de Paginações Concorrentes

**Cenário:** Múltiplas paginações acontecendo ao mesmo tempo

```
changelog-paginas:
  offset 0: PAGE_STREAM_ID=A, TRANSACTION_ID=tx-1
  offset 1: PAGE_STREAM_ID=B, TRANSACTION_ID=tx-2  ← Outra paginação!
  offset 2: PAGE_STREAM_ID=A, TRANSACTION_ID=tx-3
  offset 3: PAGE_STREAM_ID=C, TRANSACTION_ID=tx-4  ← Outra paginação!
```

**Consumer DEVE filtrar por PAGE_STREAM_ID:**
- Recebe summary com PAGE_STREAM_ID=A
- Lê changelog, ignora B e C
- Coleta apenas tx-1 e tx-3

### 5. Tamanho da Mensagem

**Limite Hard:** 1MB (1.048.576 bytes)
**Limite Seguro:** 900KB (921.600 bytes) - margem para headers/overhead

**Estimativa de Tamanho:**
```
Tamanho ≈ serialização do payload + headers + overhead Kafka (~5KB)
```

**Regra de Divisão:**
```
SE (tamanho_estimado_pagina_atual + tamanho_proximo_item > 900KB)
ENTÃO criar nova página
```

---

## API Pagination - Responsabilidades e Decisões

### Objetivo

Receber dados grandes, dividir em páginas <1MB, publicar no changelog, notificar conclusão.

### Decisões Críticas

#### 1. Quando criar uma nova página?

```
ALGORITMO de divisão:

pagina_atual = []
tamanho_estimado = 0
page_number = 1

PARA CADA item em todos_itens:
    tamanho_item = estimar_tamanho(item)
    
    SE (tamanho_estimado + tamanho_item > 900KB) E (pagina_atual não vazia):
        // Criar página
        publicar_pagina(pagina_atual, page_number, total_element_count=NULL)
        
        // Reset
        pagina_atual = []
        tamanho_estimado = 0
        page_number++
    
    pagina_atual.add(item)
    tamanho_estimado += tamanho_item

// Última página
SE pagina_atual não vazia:
    publicar_pagina(pagina_atual, page_number, total_element_count=TOTAL)
```

**Pontos de Atenção:**
- Nunca criar página vazia
- Sempre incrementar `page_number` sequencialmente
- **APENAS a última página** tem `TOTAL_ELEMENT_COUNT`

#### 2. Como estimar tamanho de um item?

**Opções:**

**A) Serializar para contar bytes (preciso, mas lento):**
```java
int tamanho = serializer.serialize(item).length;
```

**B) Estimativa conservadora (rápido, pode desperdiçar espaço):**
```java
// Se não puder serializar, use estimativa fixa
int tamanho = 1024; // 1KB por item (ajuste conforme seu domínio)
```

**C) Heurística (meio-termo):**
```java
// Ex: contar campos e estimar
int tamanho = 100 + (item.campos.size() * 200);
```

**Recomendação:** Use A se puder serializar sem deserializar. Caso contrário, use C com margem de segurança.

#### 3. Quais headers adicionar?

**Checklist ao publicar cada página:**

```
✓ PAGE_STREAM_ID = UUID da paginação inteira
✓ TRANSACTION_ID = UUID único desta página
✓ ELEMENT_COUNT = quantidade de itens na página
✓ PAGE_NUMBER = número sequencial (1, 2, 3...)
✓ MESSAGE_SOURCE = identificador do sistema produtor
✓ SENDING_TIME = System.currentTimeMillis()
✓ ORIGINAL_KEY = chave original como byte[]
✓ ORIGINAL_KEY_SERIALIZER = (opcional) nome do serializer
✓ TOTAL_ELEMENT_COUNT = (APENAS se for última página) total de itens
```

#### 4. O que enviar no Summary?

**Estrutura do SummaryDTO:**

```json
{
  "pageStreamId": "uuid-da-paginacao",
  "changelogTopic": "changelog-paginas",
  "transactionIds": ["tx-1", "tx-2", "tx-3", "tx-4"],
  "totalPages": 4,
  "totalElements": 1500,
  "messageSource": "API-PAGINATION",
  "completedAt": 1709769600000
}
```

**Quando enviar?**
- **DEPOIS** que todas as páginas foram publicadas com sucesso
- **NUNCA** enviar summary antes das páginas (consumer não acharia nada)

**Validações antes de enviar:**
```
✓ Todas as páginas foram publicadas?
✓ Lista de transactionIds está completa?
✓ totalPages == transactionIds.length?
✓ totalElements == soma de todos ELEMENT_COUNT?
```

---

## API Consumer - Responsabilidades e Decisões

### Objetivo

Receber summary, buscar páginas específicas no changelog, agregar dados, processar.

### Decisões Críticas

#### 1. Como buscar páginas no changelog?

**ABORDAGEM: Consumer temporário com filtro**

```
ALGORITMO de busca:

consumer = criar_consumer_temporario()
consumer.subscribe("changelog-paginas")

transaction_ids_faltando = Set(summary.transactionIds)
paginas_encontradas = []

ENQUANTO (transaction_ids_faltando não vazio) E (tentativas < MAX):
    records = consumer.poll(timeout=5s)
    
    PARA CADA record em records:
        // VALIDAÇÃO 1: Tem PAGE_STREAM_ID?
        SE header "PAGE_STREAM_ID" ausente:
            IGNORAR record
            CONTINUAR
        
        page_stream_id = ler_header("PAGE_STREAM_ID")
        
        // VALIDAÇÃO 2: É da paginação correta?
        SE page_stream_id != summary.pageStreamId:
            IGNORAR record (é de outra paginação)
            CONTINUAR
        
        transaction_id = record.key()
        
        // VALIDAÇÃO 3: É uma página que precisamos?
        SE transaction_id EM transaction_ids_faltando:
            paginas_encontradas.add(record)
            transaction_ids_faltando.remove(transaction_id)

consumer.close()

// VALIDAÇÃO FINAL
SE transaction_ids_faltando não vazio:
    ERRO: "Páginas não encontradas: {ids}"
    ABORTAR ou RETRY
```

**Pontos de Atenção:**
- Poll pode retornar páginas de **outras paginações** → filtrar por `PAGE_STREAM_ID`
- Poll pode retornar páginas que **não estão no summary** → filtrar por `TRANSACTION_ID`
- Poll pode retornar **vazio** → incrementar contador de tentativas vazias
- Limite de tentativas evita loop infinito

#### 2. Como tratar polls vazios?

**Problema:** Changelog pode estar temporariamente sem novos dados

**Solução: Timeout baseado em tentativas**

```
tentativas_vazias = 0
MAX_TENTATIVAS_VAZIAS = 100

LOOP:
    records = consumer.poll(5s)
    
    SE records vazio:
        tentativas_vazias++
        
        SE tentativas_vazias >= MAX_TENTATIVAS_VAZIAS:
            ERRO: "Timeout - páginas não encontradas após 100 polls vazios"
            BREAK
    SENÃO:
        tentativas_vazias = 0  // Reset ao receber dados
```

**Por que resetar?** Se recebeu dados, changelog está ativo. Só conta tentativas consecutivas vazias.

#### 3. Como reconstruir dados completos?

**Passos:**

```
1. COLETAR todas as páginas (validar quantity == summary.totalPages)

2. ENRIQUECER páginas com dados dos headers (caso não estejam no payload)

3. ORDENAR por PAGE_NUMBER:
   paginas.sort(comparando por page_number crescente)

4. VALIDAR totalização:
   soma_element_count = soma(paginas.map(p -> p.elementCount))
   
   SE soma_element_count != summary.totalElements:
       AVISO: "Divergência de contagem"

5. AGREGAR itens:
   todos_itens = []
   PARA CADA pagina em paginas (ordenadas):
       todos_itens.addAll(pagina.itens)

6. PROCESSAR todos_itens
```

**Por que ordenar?** Garantir que itens sejam processados na ordem original.

#### 4. Como enriquecer páginas com headers?

**Cenário:** Payload pode não conter todos os metadados (genericidade)

```
FUNÇÃO enriquecer(pagina, headers):
    // Preencher campos que podem estar ausentes no payload
    
    SE pagina.elementCount == null:
        pagina.elementCount = ler_header_int("ELEMENT_COUNT")
    
    SE pagina.pageNumber == null:
        pagina.pageNumber = ler_header_int("PAGE_NUMBER")
    
    SE pagina.totalElementCount == null E header "TOTAL_ELEMENT_COUNT" existe:
        pagina.totalElementCount = ler_header_int("TOTAL_ELEMENT_COUNT")
    
    SE pagina.messageSource == null:
        pagina.messageSource = ler_header_string("MESSAGE_SOURCE")
    
    SE pagina.sendingTime == null:
        pagina.sendingTime = ler_header_long("SENDING_TIME")
    
    // Preservar chave original
    SE header "ORIGINAL_KEY" existe:
        pagina.originalKey = ler_header_bytes("ORIGINAL_KEY")
```

---

## Validações Obrigatórias

### No Producer (API Pagination)

**Antes de publicar cada página:**
```
[ ] Tamanho estimado < 900KB
[ ] PAGE_STREAM_ID definido e consistente
[ ] TRANSACTION_ID único (UUID novo para cada página)
[ ] PAGE_NUMBER sequencial começando em 1
[ ] ELEMENT_COUNT > 0
[ ] TOTAL_ELEMENT_COUNT presente APENAS na última página
[ ] Todos os headers obrigatórios adicionados
[ ] ORIGINAL_KEY preservada do record original
```

**Antes de publicar summary:**
```
[ ] Todas as páginas foram publicadas com sucesso
[ ] Lista transactionIds completa (sem duplicatas)
[ ] totalPages == transactionIds.length
[ ] totalElements == soma de todos ELEMENT_COUNT
[ ] pageStreamId == PAGE_STREAM_ID das páginas
```

### No Consumer (API Consumer)

**Ao receber cada record do changelog:**
```
[ ] Header PAGE_STREAM_ID existe? (senão, IGNORAR)
[ ] PAGE_STREAM_ID == summary.pageStreamId? (senão, IGNORAR)
[ ] TRANSACTION_ID está na lista do summary? (senão, IGNORAR)
```

**Antes de processar dados agregados:**
```
[ ] Quantidade de páginas == summary.totalPages
[ ] Páginas ordenadas por PAGE_NUMBER
[ ] Soma ELEMENT_COUNT == summary.totalElements
[ ] Não há duplicatas de PAGE_NUMBER
[ ] Não há gaps em PAGE_NUMBER (1,2,3... sem pular)
```

---

## Casos de Borda e Tratamento de Erros

### Cenário 1: Página não encontrada no changelog

**Sintoma:** Consumer não encontra uma das páginas listadas no summary

**Possíveis causas:**
- Compactação removeu página antiga
- Retenção por bytes excedida
- Producer falhou ao publicar
- Offset foi consumido/deletado

**Ações:**
1. Log detalhado: `"Páginas não encontradas: {transactionIds}, pageStreamId={id}"`
2. Incrementar métrica de erro
3. **Estratégia de retry:**
	- Retentar busca N vezes (ex: 3x)
	- Aumentar timeout entre tentativas
4. Se falhar definitivamente:
	- Enviar para Dead Letter Queue
	- Alertar operações

**Prevenção:**
```yaml
# Configurar retenção adequada
retention.bytes: 2147483648  # 2GB por partição
retention.ms: 604800000      # 7 dias (fallback)
```

### Cenário 2: PAGE_STREAM_ID ausente

**Sintoma:** Mensagem no changelog sem header PAGE_STREAM_ID

**Interpretação:** Não é uma mensagem de paginação válida

**Ação:**
```
SE header "PAGE_STREAM_ID" ausente:
    LOG.warn("Mensagem ignorada: PAGE_STREAM_ID ausente, offset={}", record.offset())
    IGNORAR mensagem
    CONTINUAR para próxima
```

**NÃO:** Lançar exception, abortar processamento

### Cenário 3: Summary sem páginas correspondentes

**Sintoma:** Summary referencia transactionIds que não existem no changelog

**Possíveis causas:**
- Páginas foram compactadas/deletadas antes do summary chegar
- Problema de sincronização (summary enviado antes das páginas - BUG)
- Changelog configurado com retenção muito curta

**Ações:**
1. Validar se é problema de configuração
2. Se retenção muito curta: aumentar `retention.bytes`
3. Se bug de sincronização: revisar ordem de publicação (páginas ANTES de summary)

### Cenário 4: Timeout no poll

**Sintoma:** Consumer fica em loop esperando páginas que nunca chegam

**Causas:**
- Changelog vazio (páginas não foram publicadas)
- Consumer muito lento (max.poll.records muito alto)
- Network issues

**Soluções:**
```java
// Configurar timeouts apropriados
max.poll.records: 500  // Não muito alto
poll.timeout: 5s       // Não muito baixo

// Limite de tentativas
max_tentativas_vazias: 100
timeout_total: 10min   // Abortar após 10min
```

### Cenário 5: Páginas fora de ordem

**Sintoma:** Páginas chegam em ordem diferente (PAGE_NUMBER: 3, 1, 2)

**Impacto:** Se processar sem ordenar, dados ficam embaralhados

**Solução OBRIGATÓRIA:**
```
// SEMPRE ordenar antes de agregar
paginas.sort(Comparator.comparing(p -> p.pageNumber))
```

### Cenário 6: Duplicação de summary

**Sintoma:** Mesmo summary processado 2x (ex: consumer restart, rebalance)

**Impacto:** Dados processados duplicadamente

**Soluções:**

**A) Idempotência:**
```
// Processar summary é idempotente
// Processar 2x não causa efeitos colaterais
```

**B) Deduplicação:**
```
Set<String> summaries_processados = // cache ou DB

SE summaries_processados.contains(summary.pageStreamId):
    LOG.info("Summary já processado: {}", summary.pageStreamId)
    RETORNAR
```

**C) Commit manual offset:**
```
// Só commitar offset DEPOIS de processar com sucesso
enable.auto.commit: false
consumer.commitSync() // após processamento
```

### Cenário 7: Concorrência de múltiplas paginações

**Sintoma:** Changelog tem páginas de 10 paginações diferentes ao mesmo tempo

**Comportamento esperado:** Consumer A processa apenas páginas da paginação A

**Garantia:** Filtro por `PAGE_STREAM_ID`

```
Changelog:
  A-page-1
  B-page-1  ← Consumer A ignora
  A-page-2
  C-page-1  ← Consumer A ignora
  A-page-3

Consumer A:
  Filtra apenas PAGE_STREAM_ID=A
  Coleta: A-page-1, A-page-2, A-page-3
  Ignora: B-page-1, C-page-1
```

### Cenário 8: Mensagem muito grande (>1MB)

**Sintoma:** `RecordTooLargeException` ao publicar página

**Causa:** Estimativa de tamanho falhou, página ficou >1MB

**Soluções:**

**Imediata:**
```
CATCH RecordTooLargeException:
    LOG.error("Página muito grande: estimativa={}, real={}", estimado, real)
    
    // Dividir página em 2
    metade1 = pagina[0:len/2]
    metade2 = pagina[len/2:end]
    
    publicar(metade1)
    publicar(metade2)
```

**Preventiva:**
```
// Reduzir limite de segurança
TAMANHO_MAX = 800_000  // 800KB em vez de 900KB

// Melhorar estimativa
tamanho_real = serializar_e_medir(pagina)
SE tamanho_real > 900KB:
    ABORTAR e dividir
```

---

## Estratégias de Retry e Resiliência

### Retry no Consumer

```
CONFIGURAÇÃO:
max_retries = 3
retry_delay_ms = 5000  // 5 segundos
backoff_multiplier = 2  // Exponencial: 5s, 10s, 20s

ALGORITMO:
tentativa = 0

ENQUANTO tentativa < max_retries:
    TENTAR:
        paginas = buscar_no_changelog(summary)
        
        SE paginas.size() == summary.totalPages:
            processar(paginas)
            SUCESSO
            BREAK
        SENÃO:
            LANÇAR PaginasFaltandoException
    
    CAPTURAR Exception:
        tentativa++
        
        SE tentativa < max_retries:
            delay = retry_delay_ms * (backoff_multiplier ^ (tentativa - 1))
            LOG.warn("Retry {}/{} após {}ms", tentativa, max_retries, delay)
            ESPERAR(delay)
        SENÃO:
            LOG.error("Falha após {} tentativas", max_retries)
            enviar_para_DLQ(summary)
```

### Dead Letter Queue (DLQ)

**Quando enviar para DLQ:**
- Summary que falhou após N retries
- Páginas não encontradas mesmo após timeout
- Erros irrecuperáveis (validação falhou)

**Estrutura do DLQ:**
```
topic-summary-dlq:
  key: pageStreamId
  value: SummaryDTO
  headers:
    - ERROR_REASON: "Páginas não encontradas: [tx-1, tx-5]"
    - ERROR_TIMESTAMP: timestamp
    - RETRY_COUNT: 3
    - ORIGINAL_TOPIC: "topic-summary"
```

**Processamento do DLQ:**
- Monitor manual ou automático
- Retry com intervenção humana
- Análise de causa raiz

---

## Configuração Kafka - Parâmetros Críticos

### Producer (API Pagination)

```yaml
# Tamanho máximo de mensagem
max.request.size: 1048576  # 1MB (não aumentar, é limite hard)

# Garantia de entrega
acks: all  # Todas as réplicas confirmam
retries: 3  # Retry automático em caso de falha
enable.idempotence: true  # Evita duplicatas em retry

# Performance
compression.type: lz4  # Melhor relação velocidade/compressão
linger.ms: 10  # Aguarda 10ms para criar batch
batch.size: 16384  # 16KB de batch

# Timeout
request.timeout.ms: 30000  # 30s para receber ack
delivery.timeout.ms: 120000  # 2min timeout total
```

### Consumer (API Consumer)

```yaml
# Tamanho do poll
max.poll.records: 500  # Não muito alto (evita timeout)
fetch.min.bytes: 1024  # Mínimo 1KB antes de retornar
fetch.max.wait.ms: 500  # Espera máxima se não tiver min.bytes

# Offset
auto.offset.reset: earliest  # Ler desde o início se offset inválido
enable.auto.commit: false  # Commit manual após processar

# Timeout
session.timeout.ms: 30000  # 30s sem heartbeat = rebalance
max.poll.interval.ms: 300000  # 5min entre polls

# Deserialização
spring.json.trusted.packages: "*"  # Aceitar qualquer classe JSON
```

### Tópico changelog-paginas

```yaml
# Partições e replicação
partitions: 3  # Ajustar conforme throughput
replication.factor: 2  # Mínimo 2 para tolerância a falhas

# Retenção
cleanup.policy: compact  # Manter última versão de cada chave
retention.bytes: 2147483648  # 2GB por partição
min.compaction.lag.ms: 60000  # 1min antes de compactar
delete.retention.ms: 86400000  # 1 dia para tombstones

# Compressão
compression.type: lz4

# Segmento
segment.bytes: 1073741824  # 1GB por segmento
segment.ms: 604800000  # 7 dias
```

### Tópico topic-summary

```yaml
partitions: 1  # Baixo throughput, ordem não é crítica
replication.factor: 2

cleanup.policy: delete
retention.ms: 86400000  # 24 horas
```

---

## Monitoramento e Observabilidade

### Métricas Essenciais (Producer)

```
# Paginação
pagination.started{pageStreamId}  # Counter: paginações iniciadas
pagination.completed{pageStreamId}  # Counter: paginações concluídas
pagination.failed{pageStreamId, reason}  # Counter: paginações falhadas

# Páginas
pagination.pages.created{pageStreamId}  # Counter: páginas criadas
pagination.pages.size_bytes{pageStreamId}  # Histogram: distribuição de tamanhos
pagination.total_pages{pageStreamId}  # Gauge: total de páginas na paginação

# Summary
pagination.summary.sent{pageStreamId}  # Counter: summaries enviados

# Erros
pagination.error.too_large{pageStreamId}  # Counter: mensagens >1MB
pagination.error.publish_failed{pageStreamId, reason}  # Counter: falhas de publicação
```

### Métricas Essenciais (Consumer)

```
# Processamento
summary.received{pageStreamId}  # Counter: summaries recebidos
summary.processed{pageStreamId}  # Counter: summaries processados com sucesso
summary.failed{pageStreamId, reason}  # Counter: summaries falhados

# Páginas
pages.searched{pageStreamId}  # Counter: buscas iniciadas
pages.found{pageStreamId}  # Histogram: quantas páginas encontradas
pages.missing{pageStreamId}  # Counter: páginas não encontradas
pages.poll_attempts{pageStreamId}  # Histogram: tentativas de poll

# Performance
processing.duration_ms{pageStreamId}  # Histogram: tempo de processamento
changelog.poll_duration_ms  # Histogram: tempo de cada poll
```

### Logs Estruturados

**Formato padrão:**
```
event={tipo} pageStreamId={id} [campos específicos]
```

**Producer:**
```
event=pagination_started pageStreamId=abc123 totalItems=1500 source=API-X
event=page_created pageStreamId=abc123 pageNumber=1 transactionId=tx-1 elementCount=500 estimatedSize=850000
event=page_created pageStreamId=abc123 pageNumber=2 transactionId=tx-2 elementCount=500 estimatedSize=850000
event=page_created pageStreamId=abc123 pageNumber=3 transactionId=tx-3 elementCount=500 estimatedSize=450000 isLast=true totalElements=1500
event=summary_sent pageStreamId=abc123 totalPages=3 transactionIds=[tx-1,tx-2,tx-3]
event=pagination_completed pageStreamId=abc123 duration_ms=1234
```

**Consumer:**
```
event=summary_received pageStreamId=abc123 totalPages=3 totalElements=1500
event=changelog_search_started pageStreamId=abc123 searchingFor=[tx-1,tx-2,tx-3]
event=page_found pageStreamId=abc123 transactionId=tx-1 pageNumber=1 progress=1/3
event=page_found pageStreamId=abc123 transactionId=tx-2 pageNumber=2 progress=2/3
event=page_found pageStreamId=abc123 transactionId=tx-3 pageNumber=3 progress=3/3
event=pages_aggregated pageStreamId=abc123 totalItems=1500 validationPassed=true
event=processing_completed pageStreamId=abc123 duration_ms=5678
```

**Erros:**
```
event=error type=pages_missing pageStreamId=abc123 expected=3 found=2 missing=[tx-3] retryAttempt=1
event=error type=timeout pageStreamId=abc123 emptyPolls=100 duration_ms=600000
event=error type=validation_failed pageStreamId=abc123 reason="Element count mismatch" expected=1500 actual=1400
```

### Alertas Recomendados

```
# Críticos
- Páginas faltando após 3 retries
- Timeout em busca de páginas (>10min)
- Taxa de erro >5% em 5min

# Warnings
- Changelog com >80% de retenção usada
- Latência de processamento >1min (p95)
- Polls vazios consecutivos >50
```

---

## Testes - Cenários Obrigatórios

### Testes Unitários

**Producer:**
```
1. Divisão de páginas
   - Input: 1000 itens pequenos
   - Validar: 1 página, <1MB
   
   - Input: 1000 itens grandes
   - Validar: N páginas, cada <1MB
   
   - Input: 1 item >1MB
   - Validar: Exception ou divisão

2. Headers
   - Validar presença de todos headers obrigatórios
   - Validar TOTAL_ELEMENT_COUNT só na última
   - Validar PAGE_NUMBER sequencial

3. Summary
   - Validar transactionIds completo
   - Validar totalPages == length(transactionIds)
   - Validar totalElements correto
```

**Consumer:**
```
1. Filtro por PAGE_STREAM_ID
   - Input: Changelog com 3 paginações diferentes
   - Validar: Coleta apenas páginas da paginação correta

2. Filtro por TRANSACTION_ID
   - Input: Summary com [tx-1, tx-3], changelog com [tx-1, tx-2, tx-3]
   - Validar: Coleta apenas tx-1 e tx-3

3. Ordenação
   - Input: Páginas chegam fora de ordem (3,1,2)
   - Validar: Processamento em ordem (1,2,3)

4. Validação
   - Input: Falta 1 página
   - Validar: Erro detectado

5. Enriquecimento
   - Input: Payload sem metadados, headers completos
   - Validar: Página enriquecida corretamente
```

### Testes de Integração

```
1. Fluxo completo feliz
   - Publicar 100 itens
   - Validar paginação em 1 página
   - Validar summary correto
   - Validar consumer agrega corretamente

2. Fluxo com múltiplas páginas
   - Publicar 10.000 itens grandes
   - Validar múltiplas páginas criadas
   - Validar todas <1MB
   - Validar consumer agrega todas

3. Concorrência
   - Publicar 3 paginações simultaneamente
   - Validar 3 summaries diferentes
   - Validar consumers não misturam dados

4. Retry após falha
   - Simular falha temporária do Kafka
   - Validar retry automático
   - Validar processamento bem-sucedido

5. Timeout
   - Remover páginas do changelog (simulação)
   - Validar timeout detectado
   - Validar envio para DLQ
```

### Testes de Carga

```
1. Volume
   - 1000 paginações/segundo
   - Cada paginação com 100 páginas
   - Validar throughput, latência

2. Tamanho
   - Páginas próximas de 1MB
   - Validar sem RecordTooLargeException

3. Retenção
   - Changelog com 10GB de dados
   - Validar busca ainda funciona
   - Validar compactação efetiva

4. Concorrência de consumers
   - 10 consumers processando summaries diferentes
   - Validar não há interferência
```

---

## Checklist de Implementação

### Fase 1: Fundação

**Producer:**
- [ ] Criar estrutura de DTOs (genéricos, sem tipos específicos)
- [ ] Implementar estimativa de tamanho
- [ ] Implementar algoritmo de divisão em páginas
- [ ] Implementar adição de headers obrigatórios
- [ ] Implementar detecção de última página
- [ ] Implementar publicação no changelog
- [ ] Implementar criação e publicação de summary
- [ ] Adicionar logs estruturados
- [ ] Adicionar métricas básicas

**Consumer:**
- [ ] Criar listener de summary
- [ ] Implementar criação de consumer temporário
- [ ] Implementar busca no changelog com filtros
- [ ] Implementar validação de PAGE_STREAM_ID
- [ ] Implementar filtro por TRANSACTION_ID
- [ ] Implementar enriquecimento com headers
- [ ] Implementar ordenação por PAGE_NUMBER
- [ ] Implementar agregação de dados
- [ ] Implementar validações (quantidade, totalização)
- [ ] Adicionar logs estruturados
- [ ] Adicionar métricas básicas

### Fase 2: Resiliência

- [ ] Implementar retry no consumer (exponencial backoff)
- [ ] Implementar timeout em polls vazios
- [ ] Implementar Dead Letter Queue
- [ ] Implementar tratamento de RecordTooLargeException
- [ ] Implementar idempotência ou deduplicação
- [ ] Adicionar circuit breaker (opcional)
- [ ] Configurar commit manual de offset

### Fase 3: Testes

- [ ] Escrever testes unitários do producer
- [ ] Escrever testes unitários do consumer
- [ ] Escrever testes de integração (fluxo completo)
- [ ] Escrever testes de concorrência
- [ ] Escrever testes de falha/retry
- [ ] Executar testes de carga

### Fase 4: Observabilidade

- [ ] Configurar métricas Prometheus/Micrometer
- [ ] Criar dashboards Grafana
- [ ] Configurar alertas críticos
- [ ] Implementar tracing distribuído (opcional)
- [ ] Documentar runbooks para erros comuns

### Fase 5: Produção

- [ ] Configurar tópicos no Kafka de produção
- [ ] Validar configurações de retenção
- [ ] Testar em ambiente de staging
- [ ] Executar testes de carga em staging
- [ ] Planejar rollout gradual
- [ ] Monitorar métricas pós-deploy
- [ ] Documentar procedimentos operacionais

---

## Troubleshooting - Guia Rápido

### Sintoma: "Páginas não encontradas"

**Diagnóstico:**
```
1. Verificar logs do producer:
   - Todas as páginas foram publicadas?
   - Há erros de publicação?

2. Verificar changelog:
   - kafka-console-consumer --topic changelog-paginas --from-beginning
   - Páginas estão lá?
   - Headers estão corretos?

3. Verificar retenção:
   - kafka-topics --describe --topic changelog-paginas
   - retention.bytes excedido?
   - Compactação removeu páginas?

4. Verificar consumer:
   - Filtro por PAGE_STREAM_ID correto?
   - Timeout muito curto?
```

**Solução:**
- Se retenção: aumentar `retention.bytes`
- Se compactação: aumentar `min.compaction.lag.ms`
- Se timeout: aumentar `max_tentativas_vazias`
- Se bug: revisar lógica de filtro

### Sintoma: "RecordTooLargeException"

**Diagnóstico:**
```
1. Verificar tamanho real da página:
   - Serializar e medir bytes

2. Comparar com estimativa:
   - Estimativa estava muito baixa?

3. Verificar configuração:
   - max.request.size = 1MB?
```

**Solução:**
- Reduzir `TAMANHO_MAX_MENSAGEM` para 800KB
- Melhorar algoritmo de estimativa
- Implementar fallback: se >1MB, dividir em 2

### Sintoma: "Timeout em poll"

**Diagnóstico:**
```
1. Verificar logs:
   - Quantos polls vazios?
   - Quanto tempo total?

2. Verificar changelog:
   - Está vazio?
   - Tem dados mas filtro não pega?

3. Verificar network:
   - Latência alta?
   - Timeouts de rede?
```

**Solução:**
- Aumentar `max_tentativas_vazias`
- Aumentar `poll.timeout`
- Verificar conectividade com Kafka

### Sintoma: "Validação de totalização falhou"

**Diagnóstico:**
```
1. Verificar logs:
   - Qual divergência? (esperado vs recebido)

2. Verificar páginas:
   - Todas têm ELEMENT_COUNT?
   - TOTAL_ELEMENT_COUNT correto na última?

3. Verificar lógica de soma:
   - Está somando todas as páginas?
```

**Solução:**
- Se divergência pequena: pode ser warning, não erro
- Se divergência grande: investigar bug no producer

---

## Considerações Finais

### Quando usar esta arquitetura?

**Use quando:**
- Dados agregados ultrapassam 1MB
- Precisa de tratamento genérico (sem deserializar)
- Múltiplas paginações concorrentes
- Consumer precisa de dados completos

**NÃO use quando:**
- Dados sempre <1MB (overhead desnecessário)
- Processamento streaming (item a item)
- Precisa de busca complexa (use banco de dados)

### Alternativas

**Se dados <1MB:**
- Enviar direto, sem paginação

**Se processamento streaming:**
- Kafka Streams com KStream
- Processar item a item, não agregado

**Se precisa de busca:**
- Kafka Connect → Database
- Elasticsearch para full-text search

### Evolução futura

**Melhorias possíveis:**
1. Migrar consumer para Kafka Streams com State Store
2. Usar Schema Registry (Avro/Protobuf)
3. Implementar compressão customizada (além do Kafka)
4. Particionar changelog por região/tenant
5. Cache distribuído (Redis) para páginas frequentes

---

## Resumo Executivo

**Problema:** Kafka limita mensagens a 1MB, mas agregados podem ser maiores.

**Solução:** Sistema de paginação com 2 componentes:
1. Producer divide dados em páginas <1MB, salva no changelog
2. Consumer recebe notificação (summary), busca páginas específicas, agrega

**Princípios chave:**
- Genericidade: não deserializar payload
- Isolamento: PAGE_STREAM_ID separa paginações concorrentes
- Indexação: TRANSACTION_ID permite busca rápida
- Resiliência: retry, timeout, DLQ

**Garantias:**
- ✅ Nenhuma mensagem >1MB
- ✅ Dados completos reconstruídos corretamente
- ✅ Funciona com serializadores customizados
- ✅ Suporta múltiplas paginações simultâneas
- ✅ Tolerante a falhas com retry

**Trade-offs:**
- ➕ Flexível, genérico, escalável
- ➖ Complexidade adicional vs envio direto
- ➖ Latência maior (2 fases: pages + summary)
- ➖ Dependência de configuração correta de retenção