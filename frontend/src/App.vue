<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";

const overview = ref({ accountCount: 0, candidateCount: 0, highRiskCount: 0, openTaskCount: 0 });
const candidates = ref([]);
const selected = ref(null);
const selectedAccountId = ref("");
const question = ref("为什么该客户存在续费风险，应采取什么行动？");
const busy = ref(false);
const message = ref("");
const stream = ref(null);
const viewMode = ref("agent");
const evalCapabilities = ref({
  judgeConfigured: false,
  judgeMode: "unavailable",
  langfuseEnabled: false,
  langfuseHost: "",
  langfuseProjectId: ""
});
const evaluation = ref(null);
const previousEvaluation = ref(null);
const evaluationHistory = ref([]);
const evaluationBusy = ref(false);
const evaluationMessage = ref("");
let evaluationTimer = null;

const selectedCandidate = computed(() =>
  candidates.value.find((item) => item.accountId === selectedAccountId.value)
);
const failedEvaluationItems = computed(() =>
  (evaluation.value?.items || []).filter((item) => !item.passed)
);

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || `请求失败：${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

async function loadOverview() {
  overview.value = await request("/api/overview");
}

async function loadCandidates() {
  candidates.value = await request("/api/risk-candidates");
  if (!selectedAccountId.value && candidates.value.length) {
    selectedAccountId.value = candidates.value[0].accountId;
  }
}

async function refreshAll() {
  await Promise.all([loadOverview(), loadCandidates()]);
}

async function scan() {
  busy.value = true;
  message.value = "正在扫描未来 90 天内续费客户的经营信号…";
  try {
    const result = await request("/api/jobs/risk-scan", { method: "POST" });
    await refreshAll();
    message.value = `扫描完成，发现 ${result.candidateCount} 个风险客户。`;
  } catch (error) {
    message.value = error.message;
  } finally {
    busy.value = false;
  }
}

async function investigate(accountId = selectedAccountId.value) {
  if (!accountId) return;
  closeStream();
  busy.value = true;
  message.value = "Agent 正在读取客户、产品使用、工单、账务和处置手册…";
  selectedAccountId.value = accountId;
  try {
    selected.value = await request(`/api/accounts/${encodeURIComponent(accountId)}/investigations`, {
      method: "POST",
      body: JSON.stringify({ question: question.value })
    });
    openStream(selected.value.investigation.id);
  } catch (error) {
    message.value = error.message;
    busy.value = false;
  }
}

function openStream(id) {
  const source = new EventSource(`/api/investigations/${id}/events`);
  stream.value = source;
  source.addEventListener("update", (event) => {
    selected.value = JSON.parse(event.data);
    if (["WAITING_APPROVAL", "COMPLETED", "COMPLETED_NO_ACTION", "FAILED"].includes(selected.value.investigation.status)) {
      busy.value = false;
      message.value =
        selected.value.investigation.status === "FAILED"
          ? `调查失败：${selected.value.investigation.error}`
          : selected.value.investigation.status === "COMPLETED_NO_ACTION"
            ? "未达到风险门槛，本次无需创建处置任务。"
            : "调查完成，等待确认行动草案。";
    }
  });
  source.onerror = () => {
    source.close();
    stream.value = null;
    busy.value = false;
  };
}

function closeStream() {
  if (stream.value) {
    stream.value.close();
    stream.value = null;
  }
}

async function approve() {
  if (!selected.value) return;
  busy.value = true;
  try {
    await request(`/api/investigations/${selected.value.investigation.id}/approve-task`, {
      method: "POST",
      body: JSON.stringify({ note: "客户成功经理已确认处置方案" })
    });
    selected.value = await request(`/api/investigations/${selected.value.investigation.id}`);
    await loadOverview();
    message.value = "内部待办已创建，7 天后进入经营效果复查。";
  } catch (error) {
    message.value = error.message;
  } finally {
    busy.value = false;
  }
}

async function followUp() {
  if (!selected.value?.task) return;
  busy.value = true;
  try {
    await request(`/api/tasks/${selected.value.task.id}/follow-up`, { method: "POST" });
    selected.value = await request(`/api/investigations/${selected.value.investigation.id}`);
    message.value = "复查完成，指标结果已写入任务审计记录。";
  } catch (error) {
    message.value = error.message;
  } finally {
    busy.value = false;
  }
}

async function loadEvaluations() {
  evaluationHistory.value = await request("/api/evaluations/runs?limit=10");
  evalCapabilities.value = await request("/api/evaluations/capabilities");
  try {
    const latest = await request("/api/evaluations/latest");
    applyEvaluation(latest);
  } catch {
    evaluation.value = null;
  }
}

async function applyEvaluation(run) {
  evaluation.value = run;
  previousEvaluation.value = null;
  if (run.run.previousRunId) {
    try {
      previousEvaluation.value = await request(`/api/evaluations/runs/${run.run.previousRunId}`);
    } catch {
      previousEvaluation.value = null;
    }
  }
  if (!["PENDING", "RUNNING"].includes(run.run.status)) {
    evaluationBusy.value = false;
    if (evaluationTimer) {
      clearInterval(evaluationTimer);
      evaluationTimer = null;
    }
  }
}

async function runEvaluation(withJudge = false) {
  evaluationBusy.value = true;
  evaluationMessage.value = withJudge
    ? "正在运行 36 条金标准并调用独立 Judge…"
    : "正在运行 36 条确定性金标准评测…";
  try {
    const run = await request("/api/evaluations/runs", {
      method: "POST",
      body: JSON.stringify({ judge: withJudge, publishToLangfuse: true })
    });
    await applyEvaluation(run);
    if (evaluationTimer) clearInterval(evaluationTimer);
    evaluationTimer = window.setInterval(async () => {
      try {
        const current = await request(`/api/evaluations/runs/${run.run.id}`);
        await applyEvaluation(current);
        if (["COMPLETED", "FAILED"].includes(current.run.status)) {
          evaluationMessage.value = current.run.summary?.passed
            ? "评测通过，所有阻断阈值均满足。"
            : "评测未通过，请查看失败用例。";
          evaluationHistory.value = await request("/api/evaluations/runs?limit=10");
        }
      } catch (error) {
        evaluationMessage.value = error.message;
        evaluationBusy.value = false;
      }
    }, 1000);
  } catch (error) {
    evaluationMessage.value = error.message;
    evaluationBusy.value = false;
  }
}

async function judgeEvaluation() {
  if (!evaluation.value) return;
  evaluationBusy.value = true;
  evaluationMessage.value = "正在使用独立 Judge 评审已有评测结果…";
  try {
    evaluation.value = await request(
      `/api/evaluations/runs/${evaluation.value.run.id}/judge`,
      { method: "POST" }
    );
    evaluationMessage.value = "Judge 评审完成。";
  } catch (error) {
    evaluationMessage.value = error.message;
  } finally {
    evaluationBusy.value = false;
  }
}

function metricDelta(key) {
  if (!previousEvaluation.value?.run?.summary || !evaluation.value?.run?.summary) return "";
  const current = evaluation.value.run.summary[key] || 0;
  const previous = previousEvaluation.value.run.summary[key] || 0;
  const delta = current - previous;
  if (Math.abs(delta) < 0.0001) return "与上次持平";
  return `${delta > 0 ? "+" : ""}${(delta * 100).toFixed(1)}%`;
}

function langfuseTraceUrl(traceId) {
  const caps = evalCapabilities.value;
  if (!caps.langfuseEnabled || !caps.langfuseProjectId || !traceId) return null;
  return `${caps.langfuseHost.replace(/\/+$/, "")}/project/${caps.langfuseProjectId}/traces/${traceId}`;
}

function pick(accountId) {
  selectedAccountId.value = accountId;
}

function riskLabel(severity) {
  return severity === "HIGH" ? "高风险" : severity === "MEDIUM" ? "中风险" : "低风险";
}

function statusLabel(status) {
  const labels = {
    CREATED: "已创建",
    PLANNING: "规划中",
    EXECUTING_TOOL: "调用工具",
    ANALYZING: "分析中",
    WAITING_APPROVAL: "待确认",
    COMPLETED: "已完成",
    COMPLETED_NO_ACTION: "无需行动",
    FAILED: "失败",
    OPEN: "待处理",
    WAITING_FOLLOW_UP: "待复查",
    IMPROVED: "已改善",
    FLAT: "持平",
    WORSENED: "恶化"
  };
  return labels[status] || status;
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-";
}

function percent(value) {
  return value == null ? "-" : `${(value * 100).toFixed(1)}%`;
}

onMounted(async () => {
  await refreshAll();
  await loadEvaluations();
  if (!candidates.value.length) {
    await scan();
  }
});

onBeforeUnmount(() => {
  closeStream();
  if (evaluationTimer) clearInterval(evaluationTimer);
});
</script>

<template>
  <div class="shell">
    <header class="hero">
      <div>
        <p class="eyebrow">RENEWAL INTELLIGENCE / JAVA AGENT</p>
        <h1>客户续费经营 Agent</h1>
        <p class="subtitle">
          从风险发现到证据分析、审批建单与效果复查。RAG 只提供经营手册，Agent 负责真正执行调查任务。
        </p>
      </div>
      <div class="hero-actions">
        <nav class="view-tabs">
          <button :class="{ active: viewMode === 'agent' }" @click="viewMode = 'agent'">
            经营调查
          </button>
          <button :class="{ active: viewMode === 'evaluation' }" @click="viewMode = 'evaluation'">
            评测中心
          </button>
        </nav>
        <button v-if="viewMode === 'agent'" class="primary" :disabled="busy" @click="scan">
          {{ busy ? "处理中…" : "重新扫描风险" }}
        </button>
      </div>
    </header>

    <section v-show="viewMode === 'agent'" class="metrics">
      <article>
        <span>客户总数</span>
        <strong>{{ overview.accountCount }}</strong>
        <small>未来 90 天续费窗口</small>
      </article>
      <article>
        <span>风险客户</span>
        <strong>{{ overview.candidateCount }}</strong>
        <small>规则引擎与风险因子</small>
      </article>
      <article class="danger">
        <span>高风险</span>
        <strong>{{ overview.highRiskCount }}</strong>
        <small>优先进入客户成功处置</small>
      </article>
      <article>
        <span>进行中任务</span>
        <strong>{{ overview.openTaskCount }}</strong>
        <small>已确认待办与待复查</small>
      </article>
    </section>

    <p v-if="message && viewMode === 'agent'" class="notice">{{ message }}</p>

    <main v-show="viewMode === 'agent'" class="workspace">
      <section class="panel risk-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">RISK QUEUE</p>
            <h2>续费风险队列</h2>
          </div>
          <span class="count">{{ candidates.length }} 个候选</span>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>客户</th>
                <th>风险</th>
                <th>续费</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="candidate in candidates"
                :key="candidate.accountId"
                :class="{ active: candidate.accountId === selectedAccountId }"
                @click="pick(candidate.accountId)"
              >
                <td>
                  <strong>{{ candidate.accountName }}</strong>
                  <small>{{ candidate.industry }} · {{ candidate.accountId }}</small>
                </td>
                <td>
                  <span class="risk-pill" :class="candidate.severity.toLowerCase()">
                    {{ candidate.score.toFixed(0) }} · {{ riskLabel(candidate.severity) }}
                  </span>
                </td>
                <td>{{ candidate.renewalDays }} 天</td>
                <td>
                  <button class="text-button" @click.stop="investigate(candidate.accountId)">调查</button>
                </td>
              </tr>
              <tr v-if="!candidates.length">
                <td colspan="4" class="empty">暂无风险候选，点击右上角重新扫描。</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="manual">
          <label for="question">针对性调查问题</label>
          <textarea id="question" v-model="question" rows="3" />
          <button
            class="secondary"
            :disabled="busy || !selectedCandidate"
            @click="investigate()"
          >
            调查 {{ selectedCandidate?.accountName || "所选客户" }}
          </button>
        </div>
      </section>

      <section class="panel investigation-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">AGENT TRACE</p>
            <h2>调查与执行轨迹</h2>
          </div>
          <span v-if="selected" class="status">{{ statusLabel(selected.investigation.status) }}</span>
        </div>

        <div v-if="!selected" class="empty-state">
          <div class="radar"></div>
          <h3>选择一个风险客户开始调查</h3>
          <p>Agent 会调用客户档案、产品使用、客服工单、账务和 RAG 知识工具。</p>
        </div>

        <template v-else>
          <div class="case-head">
            <div>
              <span class="account-id">{{ selected.investigation.accountId }}</span>
              <h3>{{ selected.investigation.question }}</h3>
            </div>
            <div class="score" :class="{ hot: selected.investigation.riskScore >= 70 }">
              {{ Number(selected.investigation.riskScore || 0).toFixed(0) }}
              <small>风险分</small>
            </div>
          </div>

          <div class="trace">
            <article
              v-for="step in selected.steps"
              :key="step.id"
              class="step"
              :class="step.status.toLowerCase()"
            >
              <span class="dot"></span>
              <div>
                <div class="step-head">
                  <strong>{{ step.title }}</strong>
                  <small>{{ formatTime(step.createdAt) }}</small>
                </div>
                <p>{{ step.detail }}</p>
                <small v-if="step.durationMs">{{ step.durationMs }} ms</small>
              </div>
            </article>
          </div>

          <template v-if="selected.investigation.summary">
            <div class="analysis">
              <p class="eyebrow">EVIDENCE-BASED ANALYSIS</p>
              <h3>{{ selected.investigation.rootCause }}</h3>
              <p>{{ selected.investigation.summary }}</p>
            </div>

            <div v-if="selected.telemetry" class="telemetry-strip">
              <span>Trace {{ selected.telemetry.traceId || "未启用" }}</span>
              <span>{{ selected.telemetry.toolCalls }} 次工具调用</span>
              <span>{{ selected.telemetry.inputTokens + selected.telemetry.outputTokens }} Tokens</span>
              <a
                v-if="selected.telemetry.langfuseUrl"
                :href="selected.telemetry.langfuseUrl"
                target="_blank"
                rel="noreferrer"
              >
                在 Langfuse 查看
              </a>
            </div>

            <div class="evidence-grid">
              <article v-for="item in selected.evidence" :key="item.id" class="evidence">
                <span>{{ item.source }}</span>
                <strong>{{ item.label }}</strong>
                <p>{{ item.detail }}</p>
              </article>
            </div>

            <div v-if="selected.investigation.action" class="action-card">
              <div class="action-head">
                <div>
                  <p class="eyebrow">ACTION DRAFT / WAITING APPROVAL</p>
                  <h3>{{ selected.investigation.action.title }}</h3>
                </div>
                <span class="priority">{{ selected.investigation.action.priority }}</span>
              </div>
              <p>{{ selected.investigation.action.rationale }}</p>
              <ol>
                <li v-for="item in selected.investigation.action.steps" :key="item">{{ item }}</li>
              </ol>
              <button
                v-if="selected.investigation.status === 'WAITING_APPROVAL'"
                class="primary"
                :disabled="busy"
                @click="approve"
              >
                确认并创建内部待办
              </button>
            </div>

            <div v-if="selected.task" class="task-card">
              <div>
                <p class="eyebrow">INTERNAL TASK</p>
                <h3>{{ selected.task.title }}</h3>
                <p>{{ selected.task.ownerRole }} · {{ statusLabel(selected.task.status) }}</p>
              </div>
              <button class="secondary" :disabled="busy" @click="followUp">
                立即模拟复查
              </button>
            </div>

            <div v-if="selected.followUps.length" class="followups">
              <h3>复查记录</h3>
              <article v-for="item in selected.followUps" :key="item.id">
                <strong>{{ statusLabel(item.result) }}</strong>
                <span>{{ formatTime(item.createdAt) }}</span>
                <p>{{ item.detail }}</p>
              </article>
            </div>
          </template>
        </template>
      </section>
    </main>

    <main v-if="viewMode === 'evaluation'" class="evaluation-page">
      <section class="evaluation-hero">
        <div>
          <p class="eyebrow">EVALUATION / LANGFUSE CLOUD</p>
          <h2>Agent 质量评测中心</h2>
          <p>
            36 条固定金标准，覆盖 30 个续费风险正例和 6 个健康客户负例。确定性指标阻断回归，独立 Judge 负责质量评审。
          </p>
          <div class="capability-row">
            <span :class="{ online: evalCapabilities.langfuseEnabled }">
              Langfuse {{ evalCapabilities.langfuseEnabled ? "已配置" : "未配置" }}
            </span>
            <span :class="{ online: evalCapabilities.judgeConfigured }">
              Judge {{ evalCapabilities.judgeConfigured
                ? (evalCapabilities.judgeMode === "local" ? "本地可用" : "模型已配置")
                : "未配置" }}
            </span>
          </div>
        </div>
        <div class="evaluation-actions">
          <button class="primary" :disabled="evaluationBusy" @click="runEvaluation(false)">
            {{ evaluationBusy ? "评测运行中…" : "运行确定性评测" }}
          </button>
          <button
            class="secondary"
            :disabled="evaluationBusy || !evalCapabilities.judgeConfigured"
            @click="runEvaluation(true)"
          >
            运行并启用 Judge
          </button>
        </div>
      </section>

      <p v-if="evaluationMessage" class="notice">{{ evaluationMessage }}</p>

      <template v-if="evaluation">
        <section class="metrics evaluation-metrics">
          <article :class="{ danger: evaluation.run.summary && !evaluation.run.summary.passed }">
            <span>总体结果</span>
            <strong>{{ evaluation.run.summary?.passed ? "PASS" : "FAIL" }}</strong>
            <small>{{ evaluation.run.datasetVersion }} · {{ evaluation.run.mode }}</small>
          </article>
          <article>
            <span>完成率</span>
            <strong>{{ percent(evaluation.run.summary?.completionRate) }}</strong>
            <small>{{ metricDelta("completionRate") }}</small>
          </article>
          <article>
            <span>根因 Top-1</span>
            <strong>{{ percent(evaluation.run.summary?.rootCauseAccuracy) }}</strong>
            <small>{{ metricDelta("rootCauseAccuracy") }}</small>
          </article>
          <article>
            <span>证据覆盖</span>
            <strong>{{ percent(evaluation.run.summary?.evidenceCoverage) }}</strong>
            <small>{{ metricDelta("evidenceCoverage") }}</small>
          </article>
          <article>
            <span>行动策略</span>
            <strong>{{ percent(evaluation.run.summary?.actionPolicyPassRate) }}</strong>
            <small>{{ metricDelta("actionPolicyPassRate") }}</small>
          </article>
          <article class="danger">
            <span>严重违规</span>
            <strong>{{ evaluation.run.summary?.criticalViolations ?? "-" }}</strong>
            <small>阈值必须为 0</small>
          </article>
        </section>

        <section class="evaluation-layout">
          <div class="panel evaluation-cases">
            <div class="panel-title">
              <div>
                <p class="eyebrow">GOLDEN SET RESULTS</p>
                <h2>用例结果</h2>
              </div>
              <span class="count">
                {{ failedEvaluationItems.length }} 个失败 / {{ evaluation.items.length }} 个用例
              </span>
            </div>
            <div class="table-wrap evaluation-table">
              <table>
                <thead>
                  <tr>
                    <th>Case</th>
                    <th>根因</th>
                    <th>负责人</th>
                    <th>证据</th>
                    <th>Trace</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="item in evaluation.items"
                    :key="item.id"
                    :class="{ failed: !item.passed }"
                  >
                    <td>
                      <strong>{{ item.caseId }}</strong>
                      <small>{{ item.accountId }}</small>
                    </td>
                    <td>
                      <span :class="['match', item.expectedRootCause === item.actualRootCause ? 'ok' : 'bad']">
                        {{ item.actualRootCause || item.error }}
                      </span>
                      <small>期望 {{ item.expectedRootCause }}</small>
                    </td>
                    <td>{{ item.actualOwnerRole }}<small>期望 {{ item.expectedOwnerRole }}</small></td>
                    <td>{{ percent(item.scores.evidenceCoverage) }}<small>{{ item.durationMs }} ms</small></td>
                    <td>
                      <a
                        v-if="langfuseTraceUrl(item.traceId)"
                        class="trace-link"
                        :href="langfuseTraceUrl(item.traceId)"
                        target="_blank"
                        rel="noreferrer"
                      >
                        Langfuse
                      </a>
                      <small v-else>{{ item.traceId || "disabled" }}</small>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <aside class="panel evaluation-side">
            <div class="panel-title">
              <div>
                <p class="eyebrow">RUN CONTROL</p>
                <h2>本次运行</h2>
              </div>
            </div>
            <dl class="run-detail">
              <div><dt>Run</dt><dd>{{ evaluation.run.id }}</dd></div>
              <div><dt>状态</dt><dd>{{ evaluation.run.status }}</dd></div>
              <div><dt>Judge</dt><dd>{{ evaluation.run.judgeStatus }}</dd></div>
              <div><dt>平均耗时</dt><dd>{{ Math.round(evaluation.run.summary?.averageDurationMs || 0) }} ms</dd></div>
              <div><dt>P95</dt><dd>{{ Math.round(evaluation.run.summary?.p95DurationMs || 0) }} ms</dd></div>
              <div><dt>Tokens</dt><dd>{{ evaluation.run.summary?.totalInputTokens + evaluation.run.summary?.totalOutputTokens }}</dd></div>
              <div v-if="evaluation.run.summary?.averageJudgeScore">
                <dt>Judge</dt><dd>{{ percent(evaluation.run.summary.averageJudgeScore) }}</dd>
              </div>
            </dl>
            <button
              class="secondary"
              :disabled="evaluationBusy || !evalCapabilities.judgeConfigured || evaluation.run.status !== 'COMPLETED'"
              @click="judgeEvaluation"
            >
              对本次结果运行 Judge
            </button>
            <div class="history">
              <h3>运行历史</h3>
              <button
                v-for="run in evaluationHistory"
                :key="run.id"
                class="history-row"
                @click="request(`/api/evaluations/runs/${run.id}`).then(applyEvaluation)"
              >
                <span>{{ formatTime(run.startedAt) }}</span>
                <strong>{{ run.passed ? "PASS" : run.status }}</strong>
              </button>
            </div>
          </aside>
        </section>
      </template>
      <section v-else class="panel empty-evaluation">
        <p class="eyebrow">NO EVALUATION YET</p>
        <h2>先运行一次金标准评测</h2>
        <p>评测不会创建待办，也不会修改线上调查。</p>
      </section>
    </main>
  </div>
</template>
