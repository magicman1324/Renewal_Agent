package com.wanghongbo.renewalagent.web;

import com.wanghongbo.renewalagent.config.LangfuseProperties;
import com.wanghongbo.renewalagent.evaluation.EvaluationService;
import com.wanghongbo.renewalagent.evaluation.JudgeService;
import com.wanghongbo.renewalagent.model.Models.EvaluationCapabilities;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import com.wanghongbo.renewalagent.model.Models.EvaluationRun;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunRequest;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunView;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final JudgeService judgeService;
    private final LangfuseProperties langfuseProperties;

    public EvaluationController(
            EvaluationService evaluationService,
            JudgeService judgeService,
            LangfuseProperties langfuseProperties
    ) {
        this.evaluationService = evaluationService;
        this.judgeService = judgeService;
        this.langfuseProperties = langfuseProperties;
    }

    @GetMapping("/capabilities")
    public EvaluationCapabilities capabilities() {
        return new EvaluationCapabilities(
                judgeService.configured(),
                langfuseProperties.enabled(),
                langfuseProperties.host(),
                langfuseProperties.projectId()
        );
    }

    @GetMapping("/cases")
    public List<EvaluationCase> cases() {
        return evaluationService.loadCases();
    }

    @PostMapping("/runs")
    public EvaluationRunView create(@RequestBody(required = false) EvaluationRunRequest request) {
        return evaluationService.createRun(request);
    }

    @GetMapping("/runs/{id}")
    public EvaluationRunView get(@PathVariable String id) {
        return evaluationService.view(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluation run: " + id));
    }

    @GetMapping("/runs")
    public List<EvaluationRun> recent(@RequestParam(defaultValue = "20") int limit) {
        return evaluationService.recent(limit);
    }

    @GetMapping("/latest")
    public EvaluationRunView latest() {
        return evaluationService.latest()
                .orElseThrow(() -> new IllegalArgumentException("No evaluation run exists"));
    }

    @PostMapping("/runs/{id}/judge")
    public EvaluationRunView judge(@PathVariable String id) {
        return evaluationService.judgeRun(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail notFound(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(404);
        detail.setTitle("Evaluation resource not found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(409);
        detail.setTitle("Evaluation state conflict");
        detail.setDetail(ex.getMessage());
        return detail;
    }
}
