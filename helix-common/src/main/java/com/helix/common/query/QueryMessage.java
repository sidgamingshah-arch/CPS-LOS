package com.helix.common.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Append-only message on a {@link QueryThread}. Once written, never updated or deleted — the
 * ordered message list is the conversation record. {@code inbound} distinguishes an external
 * reply that arrived through the façade callback from an outbound/internal post.
 */
@Entity
@Table(name = "query_messages", indexes = {
        @Index(name = "idx_qmsg_ref", columnList = "queryRef")
})
public class QueryMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String queryRef;

    @Column(nullable = false, length = 120)
    private String author;

    /** HUMAN | AI | SYSTEM — the governance signal on who authored the message. */
    @Column(nullable = false, length = 20)
    private String authorType;

    @Lob
    @Column(length = 4000)
    private String body;

    /** true when this message is an external reply received through the façade callback. */
    @Column(nullable = false)
    private boolean inbound;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    public Long getId() { return id; }

    public String getQueryRef() { return queryRef; }
    public void setQueryRef(String queryRef) { this.queryRef = queryRef; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorType() { return authorType; }
    public void setAuthorType(String authorType) { this.authorType = authorType; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isInbound() { return inbound; }
    public void setInbound(boolean inbound) { this.inbound = inbound; }

    public Instant getAt() { return at; }
}
