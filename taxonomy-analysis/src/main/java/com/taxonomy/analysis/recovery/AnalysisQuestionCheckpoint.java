package com.taxonomy.analysis.recovery;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_question_checkpoint", uniqueConstraints = @UniqueConstraint(name = "uq_analysis_question", columnNames = {"run_id", "question_key"}), indexes = @Index(name = "idx_analysis_question_run", columnList = "run_id,state"))
public class AnalysisQuestionCheckpoint {
    @Id @Column(length = 101) String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, foreignKey = @ForeignKey(name = "fk_analysis_question_run")) AnalysisContinuationRun run;
    @Column(name = "question_key", nullable = false, length = 64) String questionKey;
    @Column(name = "input_hash", nullable = false, length = 64) String inputHash;
    @Column(nullable = false, length = 64) String provider;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "node_codes", nullable = false) String nodeCodes;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "detail_json") String detailJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "prompt_text") String prompt;
    @Column(nullable = false, length = 24) String state;
    @Column(nullable = false) int attempts;
    @Column(name = "started_at", nullable = false) long startedAt;
    @Column(name = "error_text", length = 2048) String error;
    protected AnalysisQuestionCheckpoint() { }
}
