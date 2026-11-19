package com.wanghongbo.renewalagent.web;

import com.wanghongbo.renewalagent.model.Models.ApproveTaskRequest;
import com.wanghongbo.renewalagent.model.Models.CreateInvestigationRequest;
import com.wanghongbo.renewalagent.model.Models.FollowUpResult;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.Investigation;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.InvestigationView;
import com.wanghongbo.renewalagent.model.Models.RiskCandidate;
import com.wanghongbo.renewalagent.model.Models.RiskScanResponse;
import com.wanghongbo.renewalagent.model.Models.SummaryResponse;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import com.wanghongbo.renewalagent.service.AgentRuntimeService;
import com.wanghongbo.renewalagent.service.FollowUpService;
import com.wanghongbo.renewalagent.service.RiskScanService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DatabaseService database;
    private final RiskScanService riskScanService;
    private final AgentRuntimeService agentRuntimeService;
    private final FollowUpService followUpService;
    private final Executor executor;

    public ApiController(
            DatabaseService database,
            RiskScanService riskScanService,
            AgentRuntimeService agentRuntimeService,
            FollowUpService followUpService,
            @Qualifier("agentExecutor") Executor executor
    ) {
        this.database = database;
        this.riskScanService = riskScanService;
        this.agentRuntimeService = agentRuntimeService;
        this.followUpService = followUpService;
        this.executor = executor;
    }

    @GetMapping("/overview")
    public SummaryResponse overview() {
        List<RiskCandidate> candidates = database.findRiskCandidates();
        return new SummaryResponse(
                database.accountCount(),
                candidates.size(),
                database.countHighRisk(),
                database.countOpenTasks()
        );
    }

    @GetMapping("/risk-candidates")
    public List<RiskCandidate> candidates() {
        return database.findRiskCandidates();
    }

    @PostMapping("/jobs/risk-scan")
    public RiskScanResponse scan() {
        return riskScanService.scan();
    }

    @PostMapping("/accounts/{accountId}/investigations")
    public InvestigationView investigate(
            @PathVariable String accountId,
            @RequestBody(required = false) CreateInvestigationRequest request
    ) {
        return agentRuntimeService.create(
                accountId,
                request == null ? null : request.question()
        );
    }

    @GetMapping("/investigations/{id}")
    public InvestigationView investigation(@PathVariable String id) {
        return agentRuntimeService.view(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown investigation: " + id));
    }

    @GetMapping("/investigations")
    public List<Investigation> investigations(@RequestParam(defaultValue = "20") int limit) {
        return agentRuntimeService.recent(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping(path = "/investigations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        agentRuntimeService.view(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown investigation: " + id));
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> streamInvestigation(id, emitter));
        return emitter;
    }

    @PostMapping("/investigations/{id}/approve-task")
    public InternalTask approve(
            @PathVariable String id,
            @RequestBody(required = false) ApproveTaskRequest request
    ) {
        return agentRuntimeService.approve(id, request == null ? null : request.note());
    }

    @GetMapping("/tasks")
    public List<InternalTask> tasks() {
        return database.findOpenTasks();
    }

    @PostMapping("/jobs/follow-ups")
    public List<FollowUpResult> followUps() {
        return followUpService.runDueFollowUps();
    }

    @PostMapping("/tasks/{id}/follow-up")
    public FollowUpResult forceFollowUp(@PathVariable String id) {
        return followUpService.forceFollowUp(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail notFound(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(404);
        detail.setTitle("Resource not found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(409);
        detail.setTitle("Invalid state");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    private void streamInvestigation(String id, SseEmitter emitter) {
        try {
            String lastPayload = "";
            for (int attempt = 0; attempt < 240; attempt++) {
                InvestigationView view = agentRuntimeService.view(id).orElse(null);
                if (view == null) {
                    emitter.completeWithError(new IllegalArgumentException("Unknown investigation"));
                    return;
                }
                String payload = view.investigation().status() + ":" + view.steps().size();
                if (!payload.equals(lastPayload)) {
                    emitter.send(SseEmitter.event().name("update").data(view));
                    lastPayload = payload;
                }
                if (isTerminal(view.investigation().status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(500);
            }
            emitter.complete();
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            emitter.completeWithError(ex);
        }
    }

    private boolean isTerminal(InvestigationStatus status) {
        return status == InvestigationStatus.WAITING_APPROVAL
                || status == InvestigationStatus.COMPLETED
                || status == InvestigationStatus.FAILED;
    }
}
