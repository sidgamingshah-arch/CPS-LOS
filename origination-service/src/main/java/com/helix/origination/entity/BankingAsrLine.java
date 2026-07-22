package com.helix.origination.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One month of account conduct within a {@link BankingAsr}. These rows are the deterministic
 * inputs to the parent ASR's computed metrics; each row carries the month's balances, credit
 * and debit summations, peak / minimum balance, total cheque returns and the utilisation
 * (drawn ÷ sanctionedLimit) the service derived deterministically for the month.
 */
@Entity
@Table(name = "banking_asr_line", indexes = {
        @Index(name = "idx_asr_line_asr", columnList = "asr_id")
})
@Getter
@Setter
public class BankingAsrLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning ASR. Ignored in JSON to avoid a parent↔child serialisation cycle. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asr_id", nullable = false)
    @JsonIgnore
    private BankingAsr asr;

    @Column(nullable = false, length = 30)
    private String monthLabel;

    private double openingBalance;
    private double closingBalance;
    private double totalCredit;
    private double totalDebit;
    private double peakBalance;
    private double minBalanceInMonth;
    /** Total cheque returns in the month (inward + outward), for display. */
    private double chequeReturns;
    /** Deterministically derived monthly utilisation (drawn ÷ sanctionedLimit) as a fraction. */
    private double utilisationPct;
}
