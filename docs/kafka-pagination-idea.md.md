# Kafka Streams - Sistema de Paginação com Summary

## Visão Geral

Sistema com dois componentes independentes:
1. **API Pagination**: Recebe dados, pagina, salva no changelog e envia summary
2. **API Consumer**: Recebe summary, consulta changelog e processa dados agregados

## Requisitos Funcionais

### Limitações
- Mensagens Kafka limitadas a **1MB**
- Agregados podem ultrapassar 1MB (ex: 3MB total)
- Changelog de páginas retém dados por **tamanho em MB** (não por quantidade)

### Headers Kafka Obrigatórios

Todas as mensagens de paginação devem conter os seguintes headers:

| Header | Tipo | Descrição | Obrigatório |
|--------|------|-----------|-------------|
| `PAGE_STREAM_ID` | String | ID único da paginação (enquanto existir = paginação ativa) | ✅ Sim |
| `TRANSACTION_ID` | String | ID único da mensagem/transação (usado como índice da página) | ✅ Sim |
| `ELEMENT_COUNT` | Integer | Total de itens nesta página específica | ✅ Sim |
| `TOTAL_ELEMENT_COUNT` | Integer | Total de elementos de TODA a paginação (presente apenas na última página) | ❌ Apenas na última |
| `PAGE_NUMBER` | Integer | Número sequencial da página (1, 2, 3...) | ✅ Sim |
| `MESSAGE_SOURCE` | String | Identificador de quem enviou a mensagem | ✅ Sim |
| `SENDING_TIME` | Long/String | Timestamp de envio (epoch ou ISO-8601) | ✅ Sim |

### Regras de Negócio

1. **Identificação de Paginação**: Se `PAGE_STREAM_ID` não existir → **ignorar mensagem**
2. **Índice da Página**: Usar `TRANSACTION_ID` como chave única de cada página no Kafka
3. **Detecção de Fim**: Paginação termina quando `TOTAL_ELEMENT_COUNT` estiver presente no header
4. **Ordenação**: Páginas devem ser processadas em ordem via `PAGE_NUMBER`

---

## Arquitetura

### Tópicos Kafka

```yaml
# Tópico de páginas (changelog)
changelog-paginas:
  partitions: 3
  replication-factor: 2
  cleanup.policy: compact  # Mantém apenas última versão de cada TRANSACTION_ID
  retention.bytes: 1073741824  # 1GB por partição
  compression.type: lz4

# Tópico de summaries
topic-summary:
  partitions: 1
  replication-factor: 2
  cleanup.policy: delete
  retention.ms: 86400000  # 24 horas
```

### Fluxo de Dados

```
┌─────────────────┐
│  API Pagination │
└────────┬────────┘
         │
         ├──→ 1. Publica páginas em changelog-paginas
         │    (chave = TRANSACTION_ID)
         │
         └──→ 2. Ao terminar (TOTAL_ELEMENT_COUNT presente)
              publica summary em topic-summary
              
┌─────────────────┐
│  API Consumer   │
└────────┬────────┘
         │
         ├──→ 3. Recebe summary do topic-summary
         │
         └──→ 4. Consulta changelog-paginas
              filtrando por TRANSACTION_IDs do summary
              
         └──→ 5. Agrega e processa dados completos
```

---

## Estruturas de Dados

### PaginaDTO (Payload das páginas no changelog)

```java
package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginaDTO {
    private String pageStreamId;      // Mesmo valor do header PAGE_STREAM_ID
    private String transactionId;     // Mesmo valor do header TRANSACTION_ID
    private Integer pageNumber;       // Número da página (1, 2, 3...)
    private Integer elementCount;     // Itens nesta página
    private Integer totalElementCount; // Total de elementos (apenas na última página)
    private String messageSource;     // Quem enviou
    private Long sendingTime;         // Timestamp de envio
    
    private List<ItemDTO> itens;      // Dados reais da página
}
```

### ItemDTO (Item individual dentro da página)

```java
package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemDTO {
    private String id;
    private String nome;
    private String descricao;
    // Seus campos específicos aqui
}
```

### SummaryDTO (Payload do summary)

```java
package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryDTO {
    private String pageStreamId;           // ID da paginação
    private String changelogTopic;         // Nome do tópico: "changelog-paginas"
    private List<String> transactionIds;   // ["tx-1", "tx-2", "tx-3", ...]
    private Integer totalPages;            // Total de páginas criadas
    private Integer totalElements;         // Total de itens (vem de TOTAL_ELEMENT_COUNT)
    private String messageSource;          // Quem criou o summary
    private Long completedAt;              // Timestamp de conclusão da paginação
}
```

---

## API Pagination - Produtor

### Dependências Maven

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

### Responsabilidades

1. Receber dados brutos
2. Dividir em páginas (< 1MB cada)
3. Publicar páginas no `changelog-paginas` com headers obrigatórios
4. Detectar fim da paginação (`TOTAL_ELEMENT_COUNT`)
5. Publicar summary no `topic-summary` após todas as páginas

### Implementação - PaginationService

```java
package com.example.service;

import com.example.dto.ItemDTO;
import com.example.dto.PaginaDTO;
import com.example.dto.SummaryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaginationService {
    
    private final KafkaTemplate<String, PaginaDTO> kafkaTemplatePaginas;
    private final KafkaTemplate<String, SummaryDTO> kafkaTemplateSummary;
    private final ObjectMapper objectMapper;
    
    private static final int TAMANHO_MAX_MENSAGEM = 900_000; // 900KB (margem de segurança)
    private static final String CHANGELOG_TOPIC = "changelog-paginas";
    private static final String SUMMARY_TOPIC = "topic-summary";
    
    /**
     * Processa uma lista de itens, divide em páginas e publica no Kafka
     */
    public void processarPaginacao(List<ItemDTO> todosItens, String messageSource) {
        String pageStreamId = UUID.randomUUID().toString();
        
        log.info("Iniciando paginação: pageStreamId={}, totalItens={}", 
            pageStreamId, todosItens.size());
        
        // Dividir em páginas
        List<PaginaDTO> paginas = dividirEmPaginas(pageStreamId, todosItens, messageSource);
        
        // Publicar páginas no changelog
        List<String> transactionIds = publicarPaginas(paginas);
        
        // Publicar summary
        enviarSummary(pageStreamId, transactionIds, todosItens.size(), messageSource);
        
        log.info("Paginação concluída: pageStreamId={}, totalPaginas={}", 
            pageStreamId, paginas.size());
    }
    
    /**
     * Divide os itens em páginas respeitando o limite de 1MB
     */
    private List<PaginaDTO> dividirEmPaginas(String pageStreamId, 
                                              List<ItemDTO> itens, 
                                              String messageSource) {
        List<PaginaDTO> paginas = new ArrayList<>();
        List<ItemDTO> paginaAtual = new ArrayList<>();
        int tamanhoEstimado = 0;
        int pageNumber = 1;
        
        for (ItemDTO item : itens) {
            int tamanhoItem = estimarTamanho(item);
            
            // Se adicionar este item ultrapassar o limite, criar nova página
            if (tamanhoEstimado + tamanhoItem > TAMANHO_MAX_MENSAGEM && !paginaAtual.isEmpty()) {
                paginas.add(criarPagina(pageStreamId, pageNumber++, paginaAtual, 
                    null, messageSource));
                paginaAtual = new ArrayList<>();
                tamanhoEstimado = 0;
            }
            
            paginaAtual.add(item);
            tamanhoEstimado += tamanhoItem;
        }
        
        // Última página (com TOTAL_ELEMENT_COUNT)
        if (!paginaAtual.isEmpty()) {
            paginas.add(criarPagina(pageStreamId, pageNumber, paginaAtual, 
                itens.size(), messageSource));
        }
        
        return paginas;
    }
    
    /**
     * Cria uma página com os metadados necessários
     */
    private PaginaDTO criarPagina(String pageStreamId, 
                                   int pageNumber, 
                                   List<ItemDTO> itens, 
                                   Integer totalElementCount,
                                   String messageSource) {
        return PaginaDTO.builder()
            .pageStreamId(pageStreamId)
            .transactionId(UUID.randomUUID().toString())
            .pageNumber(pageNumber)
            .elementCount(itens.size())
            .totalElementCount(totalElementCount)  // Null exceto na última página
            .messageSource(messageSource)
            .sendingTime(System.currentTimeMillis())
            .itens(new ArrayList<>(itens))
            .build();
    }
    
    /**
     * Estima o tamanho em bytes de um item (serializado como JSON)
     */
    private int estimarTamanho(ItemDTO item) {
        try {
            return objectMapper.writeValueAsBytes(item).length;
        } catch (Exception e) {
            log.warn("Erro ao estimar tamanho do item, usando default 1KB", e);
            return 1024; // Fallback
        }
    }
    
    /**
     * Publica todas as páginas no changelog com headers obrigatórios
     */
    private List<String> publicarPaginas(List<PaginaDTO> paginas) {
        List<String> transactionIds = new ArrayList<>();
        
        for (PaginaDTO pagina : paginas) {
            // Criar record com chave = TRANSACTION_ID
            ProducerRecord<String, PaginaDTO> record = new ProducerRecord<>(
                CHANGELOG_TOPIC,
                pagina.getTransactionId(),  // CHAVE
                pagina                      // VALOR
            );
            
            // Adicionar headers obrigatórios
            record.headers().add("PAGE_STREAM_ID", 
                pagina.getPageStreamId().getBytes());
            record.headers().add("TRANSACTION_ID", 
                pagina.getTransactionId().getBytes());
            record.headers().add("ELEMENT_COUNT", 
                String.valueOf(pagina.getElementCount()).getBytes());
            record.headers().add("PAGE_NUMBER", 
                String.valueOf(pagina.getPageNumber()).getBytes());
            record.headers().add("MESSAGE_SOURCE", 
                pagina.getMessageSource().getBytes());
            record.headers().add("SENDING_TIME", 
                String.valueOf(pagina.getSendingTime()).getBytes());
            
            // Última página tem TOTAL_ELEMENT_COUNT
            if (pagina.getTotalElementCount() != null) {
                record.headers().add("TOTAL_ELEMENT_COUNT", 
                    String.valueOf(pagina.getTotalElementCount()).getBytes());
            }
            
            // Publicar
            kafkaTemplatePaginas.send(record);
            transactionIds.add(pagina.getTransactionId());
            
            log.debug("Página publicada: pageNumber={}, transactionId={}, elementCount={}", 
                pagina.getPageNumber(), pagina.getTransactionId(), pagina.getElementCount());
        }
        
        return transactionIds;
    }
    
    /**
     * Envia o summary após todas as páginas serem publicadas
     */
    private void enviarSummary(String pageStreamId, 
                               List<String> transactionIds, 
                               int totalElements,
                               String messageSource) {
        SummaryDTO summary = SummaryDTO.builder()
            .pageStreamId(pageStreamId)
            .changelogTopic(CHANGELOG_TOPIC)
            .transactionIds(transactionIds)
            .totalPages(transactionIds.size())
            .totalElements(totalElements)
            .messageSource(messageSource)
            .completedAt(System.currentTimeMillis())
            .build();
        
        kafkaTemplateSummary.send(SUMMARY_TOPIC, pageStreamId, summary);
        
        log.info("Summary publicado: pageStreamId={}, totalPages={}, totalElements={}", 
            pageStreamId, transactionIds.size(), totalElements);
    }
}
```

### Configuração Kafka - Producer

```yaml
# application.yml (API Pagination)
spring:
  kafka:
    bootstrap-servers: localhost:9092
    
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        max.request.size: 1048576  # 1MB
        compression.type: lz4
        linger.ms: 10
        batch.size: 16384
        acks: all  # Garantir que mensagens foram recebidas
```

### Bean de Configuração

```java
package com.example.config;

import com.example.dto.PaginaDTO;
import com.example.dto.SummaryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    
    // KafkaTemplate já é auto-configurado pelo Spring Boot
    // mas você pode customizar se necessário
}
```

---

## API Consumer - Consumidor

### Dependências Maven

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### Responsabilidades

1. Receber summary do `topic-summary`
2. Criar consumer temporário para `changelog-paginas`
3. Buscar apenas páginas com `TRANSACTION_ID` listados no summary
4. Validar headers (`PAGE_STREAM_ID` obrigatório)
5. Agregar dados completos
6. Processar

### Implementação - SummaryConsumerService

```java
package com.example.consumer;

import com.example.dto.ItemDTO;
import com.example.dto.PaginaDTO;
import com.example.dto.SummaryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SummaryConsumerService {
    
    private final ConsumerFactory<String, PaginaDTO> consumerFactory;
    
    /**
     * Listener do tópico de summaries
     */
    @KafkaListener(topics = "topic-summary", groupId = "consumer-group")
    public void receberSummary(
        @Payload SummaryDTO summary,
        @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        
        log.info("Summary recebido: pageStreamId={}, totalPages={}, totalElements={}", 
            summary.getPageStreamId(), summary.getTotalPages(), summary.getTotalElements());
        
        try {
            // Buscar páginas no changelog
            List<PaginaDTO> paginas = buscarPaginasNoChangelog(summary);
            
            // Validar se recebeu todas as páginas esperadas
            if (paginas.size() != summary.getTotalPages()) {
                log.error("Páginas faltando! Esperado={}, Recebido={}, pageStreamId={}", 
                    summary.getTotalPages(), paginas.size(), summary.getPageStreamId());
                // Implementar estratégia de retry ou dead letter queue
                return;
            }
            
            // Ordenar por PAGE_NUMBER para processar em ordem
            paginas.sort(Comparator.comparing(PaginaDTO::getPageNumber));
            
            // Validar total de elementos
            int totalItensRecebidos = paginas.stream()
                .mapToInt(PaginaDTO::getElementCount)
                .sum();
            
            if (totalItensRecebidos != summary.getTotalElements()) {
                log.warn("Total de elementos divergente! Esperado={}, Recebido={}", 
                    summary.getTotalElements(), totalItensRecebidos);
            }
            
            // Agregar e processar dados completos
            processarDadosCompletos(summary, paginas);
            
        } catch (Exception e) {
            log.error("Erro ao processar summary: pageStreamId={}", 
                summary.getPageStreamId(), e);
            // Implementar estratégia de retry
        }
    }
    
    /**
     * Busca as páginas específicas no changelog usando os TRANSACTION_IDs do summary
     */
    private List<PaginaDTO> buscarPaginasNoChangelog(SummaryDTO summary) {
        Consumer<String, PaginaDTO> consumer = consumerFactory.createConsumer();
        consumer.subscribe(Collections.singletonList(summary.getChangelogTopic()));
        
        Set<String> transactionIdsRestantes = new HashSet<>(summary.getTransactionIds());
        List<PaginaDTO> paginasEncontradas = new ArrayList<>();
        
        int tentativasVazias = 0;
        int maxTentativasVazias = 100; // Timeout após 100 polls sem dados
        
        log.debug("Buscando {} páginas no changelog: {}", 
            transactionIdsRestantes.size(), summary.getChangelogTopic());
        
        while (!transactionIdsRestantes.isEmpty() && tentativasVazias < maxTentativasVazias) {
            ConsumerRecords<String, PaginaDTO> records = consumer.poll(Duration.ofSeconds(5));
            
            if (records.isEmpty()) {
                tentativasVazias++;
                log.debug("Poll vazio {}/{}", tentativasVazias, maxTentativasVazias);
                continue;
            }
            
            tentativasVazias = 0; // Reset contador ao receber dados
            
            for (ConsumerRecord<String, PaginaDTO> record : records) {
                // VALIDAÇÃO 1: Header PAGE_STREAM_ID obrigatório
                org.apache.kafka.common.header.Header pageStreamIdHeader = 
                    record.headers().lastHeader("PAGE_STREAM_ID");
                
                if (pageStreamIdHeader == null) {
                    log.warn("Mensagem ignorada: PAGE_STREAM_ID ausente, offset={}", 
                        record.offset());
                    continue;
                }
                
                String pageStreamId = new String(pageStreamIdHeader.value());
                
                // VALIDAÇÃO 2: Pertence a este summary?
                if (!pageStreamId.equals(summary.getPageStreamId())) {
                    continue; // Página de outra paginação, ignorar
                }
                
                String transactionId = record.key();
                
                // VALIDAÇÃO 3: É uma das páginas que precisamos?
                if (transactionIdsRestantes.contains(transactionId)) {
                    PaginaDTO pagina = record.value();
                    
                    // Enriquecer com dados dos headers se necessário
                    enriquecerComHeaders(pagina, record.headers());
                    
                    paginasEncontradas.add(pagina);
                    transactionIdsRestantes.remove(transactionId);
                    
                    log.debug("Página encontrada: pageNumber={}, transactionId={}, progresso={}/{}", 
                        pagina.getPageNumber(), transactionId, 
                        paginasEncontradas.size(), summary.getTotalPages());
                }
            }
        }
        
        consumer.close();
        
        // Log de páginas não encontradas
        if (!transactionIdsRestantes.isEmpty()) {
            log.error("Páginas não encontradas no changelog: pageStreamId={}, faltando={}", 
                summary.getPageStreamId(), transactionIdsRestantes);
        }
        
        return paginasEncontradas;
    }
    
    /**
     * Enriquece a página com informações dos headers
     */
    private void enriquecerComHeaders(PaginaDTO pagina, Headers headers) {
        // ELEMENT_COUNT
        org.apache.kafka.common.header.Header elementCount = headers.lastHeader("ELEMENT_COUNT");
        if (elementCount != null && pagina.getElementCount() == null) {
            pagina.setElementCount(Integer.parseInt(new String(elementCount.value())));
        }
        
        // TOTAL_ELEMENT_COUNT (apenas última página)
        org.apache.kafka.common.header.Header totalElementCount = 
            headers.lastHeader("TOTAL_ELEMENT_COUNT");
        if (totalElementCount != null) {
            pagina.setTotalElementCount(Integer.parseInt(new String(totalElementCount.value())));
        }
        
        // PAGE_NUMBER
        org.apache.kafka.common.header.Header pageNumber = headers.lastHeader("PAGE_NUMBER");
        if (pageNumber != null && pagina.getPageNumber() == null) {
            pagina.setPageNumber(Integer.parseInt(new String(pageNumber.value())));
        }
        
        // MESSAGE_SOURCE
        org.apache.kafka.common.header.Header messageSource = headers.lastHeader("MESSAGE_SOURCE");
        if (messageSource != null && pagina.getMessageSource() == null) {
            pagina.setMessageSource(new String(messageSource.value()));
        }
        
        // SENDING_TIME
        org.apache.kafka.common.header.Header sendingTime = headers.lastHeader("SENDING_TIME");
        if (sendingTime != null && pagina.getSendingTime() == null) {
            pagina.setSendingTime(Long.parseLong(new String(sendingTime.value())));
        }
    }
    
    /**
     * Processa os dados completos após agregar todas as páginas
     */
    private void processarDadosCompletos(SummaryDTO summary, List<PaginaDTO> paginas) {
        // Agregar todos os itens de todas as páginas
        List<ItemDTO> todosItens = paginas.stream()
            .flatMap(p -> p.getItens().stream())
            .collect(Collectors.toList());
        
        log.info("Dados completos agregados: pageStreamId={}, totalPaginas={}, totalItens={}", 
            summary.getPageStreamId(), paginas.size(), todosItens.size());
        
        // ===== SEU PROCESSAMENTO AQUI =====
        // Exemplos:
        // - Salvar no banco de dados
        // - Enviar para outro sistema
        // - Gerar relatório
        // - Processar regras de negócio
        
        for (ItemDTO item : todosItens) {
            // Processar cada item
            log.debug("Processando item: id={}, nome={}", item.getId(), item.getNome());
        }
        
        log.info("Processamento concluído: pageStreamId={}", summary.getPageStreamId());
    }
}
```

### Configuração Kafka - Consumer

```yaml
# application.yml (API Consumer)
spring:
  kafka:
    bootstrap-servers: localhost:9092
    
    consumer:
      group-id: consumer-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        max.poll.records: 500  # Quantidade de mensagens por poll
        fetch.min.bytes: 1024
        fetch.max.wait.ms: 500
      auto-offset-reset: earliest
      enable-auto-commit: true
```

---

## Tratamento de Erros e Edge Cases

### Cenários e Soluções

| Cenário | Problema | Solução |
|---------|----------|---------|
| **Página faltando no changelog** | Consumer não encontra uma das páginas | Timeout após N polls vazios, log de erro com transactionIds faltantes |
| **`PAGE_STREAM_ID` ausente** | Mensagem não é paginação válida | Ignorar mensagem, não processar |
| **Summary sem páginas correspondentes** | Páginas foram compactadas/removidas | Configurar `retention.bytes` adequadamente, alertar ops |
| **Timeout no poll** | Changelog muito grande ou lento | Aumentar `max.poll.records` e timeout do poll |
| **Páginas fora de ordem** | Processamento em ordem errada | Ordenar por `PAGE_NUMBER` antes de processar |
| **Duplicação de summary** | Summary processado 2x | Idempotência no processamento ou deduplicação |
| **Concorrência** | Múltiplas paginações simultâneas | `PAGE_STREAM_ID` garante isolamento |

### Implementação de Retry

```java
@Service
@Slf4j
public class SummaryConsumerServiceComRetry {
    
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;
    
    @KafkaListener(topics = "topic-summary", groupId = "consumer-group")
    public void receberSummary(@Payload SummaryDTO summary) {
        int tentativa = 0;
        boolean sucesso = false;
        
        while (tentativa < MAX_RETRIES && !sucesso) {
            try {
                tentativa++;
                log.info("Processando summary (tentativa {}/{}): pageStreamId={}", 
                    tentativa, MAX_RETRIES, summary.getPageStreamId());
                
                List<PaginaDTO> paginas = buscarPaginasNoChangelog(summary);
                
                if (paginas.size() == summary.getTotalPages()) {
                    processarDadosCompletos(summary, paginas);
                    sucesso = true;
                } else {
                    log.warn("Páginas faltando na tentativa {}", tentativa);
                    Thread.sleep(RETRY_DELAY_MS);
                }
                
            } catch (Exception e) {
                log.error("Erro na tentativa {}: {}", tentativa, e.getMessage());
                if (tentativa < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        if (!sucesso) {
            log.error("Falha após {} tentativas: pageStreamId={}", 
                MAX_RETRIES, summary.getPageStreamId());
            // Enviar para Dead Letter Queue
        }
    }
}
```

---

## Monitoramento e Métricas

### Métricas Importantes

```java
@Service
public class PaginationMetricsService {
    
    private final MeterRegistry meterRegistry;
    
    public void registrarPaginaCriada(String pageStreamId) {
        meterRegistry.counter("pagination.page.created", 
            "pageStreamId", pageStreamId).increment();
    }
    
    public void registrarSummaryEnviado(String pageStreamId, int totalPages) {
        meterRegistry.counter("pagination.summary.sent", 
            "pageStreamId", pageStreamId).increment();
        
        meterRegistry.gauge("pagination.total.pages", totalPages);
    }
    
    public void registrarPaginaProcessada(String pageStreamId) {
        meterRegistry.counter("pagination.page.processed", 
            "pageStreamId", pageStreamId).increment();
    }
    
    public void registrarErro(String pageStreamId, String tipo) {
        meterRegistry.counter("pagination.error", 
            "pageStreamId", pageStreamId,
            "type", tipo).increment();
    }
}
```

### Logs Estruturados

```java
log.info("event=pagination_started pageStreamId={} totalItems={}", 
    pageStreamId, totalItems);

log.info("event=page_published pageStreamId={} pageNumber={} transactionId={}", 
    pageStreamId, pageNumber, transactionId);

log.info("event=summary_sent pageStreamId={} totalPages={}", 
    pageStreamId, totalPages);

log.info("event=pagination_completed pageStreamId={} totalPages={} totalItems={}", 
    pageStreamId, totalPages, totalItems);
```

---

## Testes

### Teste Unitário - Divisão de Páginas

```java
@Test
public void testDividirEmPaginas() {
    List<ItemDTO> itens = criarItens(1000); // 1000 itens
    
    List<PaginaDTO> paginas = paginationService.dividirEmPaginas(
        "test-stream-id", itens, "TEST");
    
    // Verificar que páginas foram criadas
    assertThat(paginas).isNotEmpty();
    
    // Última página deve ter TOTAL_ELEMENT_COUNT
    PaginaDTO ultimaPagina = paginas.get(paginas.size() - 1);
    assertThat(ultimaPagina.getTotalElementCount()).isEqualTo(1000);
    
    // Outras páginas não devem ter
    for (int i = 0; i < paginas.size() - 1; i++) {
        assertThat(paginas.get(i).getTotalElementCount()).isNull();
    }
    
    // Validar tamanho < 1MB
    for (PaginaDTO pagina : paginas) {
        int tamanho = estimarTamanho(pagina);
        assertThat(tamanho).isLessThan(1_048_576);
    }
}
```

### Teste de Integração - Fluxo Completo

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"changelog-paginas", "topic-summary"})
public class PaginationIntegrationTest {
    
    @Autowired
    private PaginationService paginationService;
    
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    @Test
    public void testFluxoCompleto() throws Exception {
        List<ItemDTO> itens = criarItens(100);
        
        // Processar paginação
        paginationService.processarPaginacao(itens, "TEST");
        
        // Aguardar processamento assíncrono
        Thread.sleep(5000);
        
        // Verificar que summary foi publicado
        // Verificar que páginas foram publicadas no changelog
        // Verificar que consumer processou tudo
    }
}
```

---

## Checklist de Implementação

### API Pagination
- [ ] Criar DTOs (PaginaDTO, ItemDTO, SummaryDTO)
- [ ] Implementar PaginationService
- [ ] Configurar KafkaTemplate para páginas e summary
- [ ] Adicionar headers obrigatórios nas mensagens
- [ ] Implementar lógica de divisão em páginas
- [ ] Detectar última página (TOTAL_ELEMENT_COUNT)
- [ ] Publicar summary após todas as páginas
- [ ] Adicionar logs e métricas
- [ ] Criar testes unitários
- [ ] Criar testes de integração

### API Consumer
- [ ] Criar DTOs (mesmos da API Pagination)
- [ ] Implementar SummaryConsumerService
- [ ] Configurar ConsumerFactory
- [ ] Implementar listener do summary
- [ ] Criar consumer temporário para changelog
- [ ] Validar header PAGE_STREAM_ID
- [ ] Filtrar por TRANSACTION_IDs do summary
- [ ] Enriquecer páginas com headers
- [ ] Ordenar por PAGE_NUMBER
- [ ] Agregar dados completos
- [ ] Implementar lógica de processamento
- [ ] Adicionar tratamento de erros
- [ ] Implementar retry
- [ ] Adicionar logs e métricas
- [ ] Criar testes

### Infraestrutura
- [ ] Criar tópico `changelog-paginas` (compacted)
- [ ] Criar tópico `topic-summary` (delete)
- [ ] Configurar retenção por MB no changelog
- [ ] Configurar compressão (lz4)
- [ ] Configurar monitoramento (Prometheus/Grafana)
- [ ] Configurar alertas (páginas faltando, timeouts)

---

## Troubleshooting

### Problema: Páginas não encontradas no changelog

**Sintoma**: Consumer não encontra algumas páginas listadas no summary

**Possíveis causas**:
1. Compactação removeu páginas antigas
2. Retenção por bytes excedida
3. Producer falhou ao publicar
4. Offset foi consumido antes do summary chegar

**Solução**:
```yaml
# Aumentar retenção
retention.bytes: 2147483648  # 2GB

# Desabilitar compactação temporariamente para debug
cleanup.policy: delete
```

### Problema: Timeout no poll

**Sintoma**: Consumer fica em loop infinito esperando páginas

**Solução**:
```java
// Reduzir max.poll.records
max.poll.records: 100

// Aumentar timeout do poll
consumer.poll(Duration.ofSeconds(30))

// Adicionar limite de tentativas
int maxTentativas = 50;
```

### Problema: Mensagens muito grandes

**Sintoma**: `RecordTooLargeException`

**Solução**:
```java
// Reduzir tamanho máximo da página
private static final int TAMANHO_MAX_MENSAGEM = 800_000; // 800KB

// Ou aumentar limite do Kafka (não recomendado)
max.request.size: 2097152  # 2MB
```

---

## Próximos Passos

1. **Dead Letter Queue**: Implementar DLQ para summaries que falharam
2. **Idempotência**: Garantir que processar 2x o mesmo summary não cause problemas
3. **Compressão**: Avaliar diferentes algoritmos (gzip vs lz4 vs snappy)
4. **Particionamento**: Usar múltiplas partições para escalar
5. **Kafka Streams**: Migrar consumer para Kafka Streams com State Store
6. **Schema Registry**: Usar Avro/Protobuf para versionamento de schemas

---

## Referências

- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [Spring Kafka](https://spring.io/projects/spring-kafka)
- [Kafka Compaction](https://kafka.apache.org/documentation/#compaction)
- [Kafka Producer Configuration](https://kafka.apache.org/documentation/#producerconfigs)
- [Kafka Consumer Configuration](https://kafka.apache.org/documentation/#consumerconfigs)