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

const selectedCandidate = computed(() =>
  candidates.value.find((item) => item.accountId === selectedAccountId.value)
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
    if (["WAITING_APPROVAL", "COMPLETED", "FAILED"].includes(selected.value.investigation.status)) {
      busy.value = false;
      message.value =
        selected.value.investigation.status === "FAILED"
          ? `调查失败：${selected.value.investigation.error}`
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

onMounted(async () => {
  await refreshAll();
  if (!candidates.value.length) {
    await scan();
  }
});

onBeforeUnmount(closeStream);
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
      <button class="primary" :disabled="busy" @click="scan">
        {{ busy ? "处理中…" : "重新扫描风险" }}
      </button>
    </header>

    <section class="metrics">
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

    <p v-if="message" class="notice">{{ message }}</p>

    <main class="workspace">
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
  </div>
</template>
