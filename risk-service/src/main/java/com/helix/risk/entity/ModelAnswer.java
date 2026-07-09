package com.helix.risk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One captured answer for a question on a {@link ModelInstance}. For ITERATIVE
 * questions there is one row per (questionKey, itemIndex, itemFieldKey); for all
 * other types {@code itemIndex} is null and {@code itemFieldKey} is null.
 */
@Entity
@Table(name = "model_answers", indexes = {
        @Index(name = "idx_model_answer_instance", columnList = "instanceId")
})
@Getter
@Setter
public class ModelAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 40)
    private String sectionKey;

    @Column(name = "question_key", nullable = false, length = 60)
    private String questionKey;

    /** Non-null only for ITERATIVE questions (the row index within the repeating group). */
    private Integer itemIndex;

    /** Non-null only for ITERATIVE item fields (which sub-field of the repeating row). */
    @Column(length = 60)
    private String itemFieldKey;

    @Column(length = 1000)
    private String valueText;

    private Double valueNum;

    /** Where the answer came from: SYSTEM (module-sourced) | AI (model-recommended) | HUMAN (entered). */
    @Column(length = 10)
    private String source;

    /** For module-sourced or model-recommended answers: why this value/score (traceability). */
    @Column(length = 600)
    private String rationale;
}
