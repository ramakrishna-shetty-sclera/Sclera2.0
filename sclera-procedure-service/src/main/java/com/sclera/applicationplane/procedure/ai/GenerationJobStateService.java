package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GenerationJob;
import com.sclera.applicationplane.procedure.domain.GenerationJobStatus;
import com.sclera.applicationplane.procedure.repository.GeneratedQuestionRepository;
import com.sclera.applicationplane.procedure.repository.GenerationJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transactional state transitions of a generation job, separated from
 * {@link GenerationJobRunner} so each is a real proxied transaction (a
 * self-invoked @Transactional method would run without one). COMPLETED and its
 * candidates commit together: a job is never COMPLETED with half its
 * candidates missing.
 */
@Service
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class GenerationJobStateService {

    private final GenerationJobRepository jobs;
    private final GeneratedQuestionRepository candidates;

    public GenerationJobStateService(GenerationJobRepository jobs, GeneratedQuestionRepository candidates) {
        this.jobs = jobs;
        this.candidates = candidates;
    }

    @Transactional
    public GenerationJob markRunning(UUID jobId, UUID orgId) {
        GenerationJob job = jobs.findByIdAndOrgId(jobId, orgId).orElseThrow();
        job.setStatus(GenerationJobStatus.RUNNING);
        return jobs.saveAndFlush(job);
    }

    @Transactional
    public void complete(UUID jobId, UUID orgId, List<GeneratedQuestion> produced,
                         String model, long inputTokens, long outputTokens) {
        candidates.saveAll(produced);
        GenerationJob job = jobs.findByIdAndOrgId(jobId, orgId).orElseThrow();
        job.setStatus(GenerationJobStatus.COMPLETED);
        if (model != null) {
            job.setModel(model);
        }
        job.addUsage(inputTokens, outputTokens);
        job.setCompletedAt(OffsetDateTime.now());
        jobs.saveAndFlush(job);
    }

    @Transactional
    public void markFailed(UUID jobId, String message) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(GenerationJobStatus.FAILED);
            String error = message == null || message.isBlank() ? "Generation failed" : message;
            job.setError(error.length() <= 2000 ? error : error.substring(0, 2000));
            job.setCompletedAt(OffsetDateTime.now());
            jobs.saveAndFlush(job);
        });
    }
}
