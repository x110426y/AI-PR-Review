<script setup>
import { ref } from 'vue'

// ========== 状态定义 ==========
const prUrl = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)

// ========== 常量 ==========
const API_BASE = 'http://localhost:8080'

// 风险级别对应的颜色和标签
const severityConfig = {
  HIGH:   { label: '高', color: '#e74c3c', bg: '#fde8e8' },
  MEDIUM: { label: '中', color: '#f39c12', bg: '#fef3cd' },
  LOW:    { label: '低', color: '#3498db', bg: '#e8f4fd' }
}

// 风险分类的中文映射
const categoryLabels = {
  SECURITY:    '安全',
  PERFORMANCE: '性能',
  LOGIC:       '逻辑',
  STYLE:       '代码风格'
}

// ========== 核心方法：发起审查 ==========
async function startReview() {
  // 清除旧状态
  error.value = ''
  result.value = null

  // 简单校验
  const trimmed = prUrl.value.trim()
  if (!trimmed) {
    error.value = '请输入 GitHub PR 链接'
    return
  }
  if (!/^https?:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+/i.test(trimmed)) {
    error.value = 'PR 链接格式不正确，示例：https://github.com/owner/repo/pull/123'
    return
  }

  loading.value = true
  try {
    const url = `${API_BASE}/api/review?prUrl=${encodeURIComponent(trimmed)}`
    const resp = await fetch(url)

    if (!resp.ok) {
      // 尝试解析后端错误消息
      let msg = `请求失败 (HTTP ${resp.status})`
      try {
        const errBody = await resp.json()
        if (errBody.message) msg = errBody.message
      } catch (_) { /* ignore */ }
      throw new Error(msg)
    }

    const data = await resp.json()
    result.value = data
  } catch (e) {
    error.value = e.message || '网络异常，请确认后端服务已启动'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="app-wrapper">
    <!-- ========== 顶部标题栏 ========== -->
    <header class="app-header">
      <h1>🔍 AI PR Review 助手</h1>
      <p class="subtitle">基于 Spring AI + DeepSeek 的智能代码审查工具</p>
    </header>

    <!-- ========== 搜索区 ========== -->
    <section class="search-card">
      <div class="input-row">
        <input
          v-model="prUrl"
          type="url"
          placeholder="输入 GitHub PR 链接，例如 https://github.com/owner/repo/pull/123"
          :disabled="loading"
          @keyup.enter="startReview"
        />
        <button :disabled="loading" @click="startReview">
          <template v-if="loading">
            <span class="spinner"></span>
            审查中...
          </template>
          <template v-else>
            开始 Review
          </template>
        </button>
      </div>

      <!-- 错误提示条 -->
      <transition name="fade">
        <div v-if="error" class="error-banner">
          <span class="error-icon">⚠️</span>
          <span>{{ error }}</span>
        </div>
      </transition>
    </section>

    <!-- ========== Loading 状态 ========== -->
    <transition name="fade">
      <div v-if="loading" class="loading-card">
        <div class="loading-dots">
          <span></span><span></span><span></span>
        </div>
        <p>AI 正在分析 PR 变更，请稍候...</p>
        <p class="loading-hint">大模型推理通常需要 10–30 秒</p>
      </div>
    </transition>

    <!-- ========== 结果展示区 ========== -->
    <template v-if="result">
      <!-- 总结卡片 -->
      <section class="card summary-card">
        <h2>📋 变更总结</h2>
        <p class="summary-text">{{ result.summary || '（无总结内容）' }}</p>
      </section>

      <!-- 风险项卡片 -->
      <section class="card risks-card">
        <h2>🚨 风险项 <span class="count-badge">{{ result.risks?.length || 0 }}</span></h2>
        <div v-if="!result.risks || result.risks.length === 0" class="empty-state">
          ✅ 未发现明显风险
        </div>
        <ul v-else class="risk-list">
          <li v-for="(risk, idx) in result.risks" :key="idx" class="risk-item">
            <div class="risk-header">
              <span
                class="severity-badge"
                :style="{
                  color: severityConfig[risk.severity]?.color || '#666',
                  background: severityConfig[risk.severity]?.bg || '#f0f0f0'
                }"
              >
                {{ severityConfig[risk.severity]?.label || risk.severity }}
              </span>
              <span class="risk-category">
                {{ categoryLabels[risk.category] || risk.category }}
              </span>
              <span v-if="risk.location" class="risk-location">
                📍 {{ risk.location }}
              </span>
            </div>
            <p class="risk-desc">{{ risk.description }}</p>
            <div v-if="risk.recommendation" class="risk-recommendation">
              <strong>💡 建议：</strong>{{ risk.recommendation }}
            </div>
          </li>
        </ul>
      </section>

      <!-- 改进建议卡片 -->
      <section class="card suggestions-card">
        <h2>💡 改进建议 <span class="count-badge">{{ result.suggestions?.length || 0 }}</span></h2>
        <div v-if="!result.suggestions || result.suggestions.length === 0" class="empty-state">
          ✅ 暂无改进建议
        </div>
        <ul v-else class="suggestion-list">
          <li v-for="(sug, idx) in result.suggestions" :key="idx" class="suggestion-item">
            <div class="suggestion-header">
              <span v-if="sug.location" class="suggestion-location">📍 {{ sug.location }}</span>
            </div>
            <p class="suggestion-desc">{{ sug.description }}</p>
            <div v-if="sug.refactoredCode" class="code-block-wrapper">
              <pre><code>{{ sug.refactoredCode }}</code></pre>
            </div>
          </li>
        </ul>
      </section>
    </template>

    <!-- ========== 底部信息 ========== -->
    <footer class="app-footer">
      <p>AI PR Review Assistant · Powered by Spring Boot 3.2 + Spring AI + DeepSeek</p>
    </footer>
  </div>
</template>

<style scoped>
/* ========== 全局重置 & 基础 ========== */
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

.app-wrapper {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 20px 60px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
    'Helvetica Neue', Arial, 'Noto Sans SC', sans-serif;
  color: #2c3e50;
  background: #f5f7fa;
  min-height: 100vh;
}

/* ========== 头部 ========== */
.app-header {
  text-align: center;
  padding: 32px 0 24px;
}
.app-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: #1a365d;
  letter-spacing: -0.5px;
}
.subtitle {
  margin-top: 6px;
  font-size: 14px;
  color: #718096;
}

/* ========== 搜索卡片 ========== */
.search-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
}
.input-row {
  display: flex;
  gap: 12px;
}
.input-row input {
  flex: 1;
  padding: 12px 16px;
  border: 2px solid #e2e8f0;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}
.input-row input:focus {
  border-color: #3182ce;
}
.input-row input:disabled {
  background: #f7fafc;
  cursor: not-allowed;
}
.input-row input::placeholder {
  color: #a0aec0;
}

.input-row button {
  padding: 12px 28px;
  background: #2b6cb0;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: background 0.2s, transform 0.1s;
}
.input-row button:hover:not(:disabled) {
  background: #2c5282;
}
.input-row button:active:not(:disabled) {
  transform: scale(0.98);
}
.input-row button:disabled {
  background: #a0c4e8;
  cursor: not-allowed;
}

/* 按钮内小转圈 */
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ========== 错误条 ========== */
.error-banner {
  margin-top: 14px;
  padding: 12px 16px;
  background: #fff5f5;
  border: 1px solid #fc8181;
  border-radius: 8px;
  color: #c53030;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.error-icon {
  font-size: 18px;
  flex-shrink: 0;
}

/* ========== Loading 卡片 ========== */
.loading-card {
  background: #fff;
  border-radius: 12px;
  padding: 48px 24px;
  text-align: center;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
}
.loading-dots {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 16px;
}
.loading-dots span {
  width: 10px;
  height: 10px;
  background: #3182ce;
  border-radius: 50%;
  animation: bounce 1.2s infinite ease-in-out both;
}
.loading-dots span:nth-child(1) { animation-delay: -0.32s; }
.loading-dots span:nth-child(2) { animation-delay: -0.16s; }
@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
.loading-card p {
  color: #4a5568;
  font-size: 15px;
}
.loading-hint {
  margin-top: 6px;
  font-size: 13px;
  color: #a0aec0;
}

/* ========== 通用卡片 ========== */
.card {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
}
.card h2 {
  font-size: 18px;
  font-weight: 700;
  color: #1a365d;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.count-badge {
  font-size: 13px;
  background: #edf2f7;
  color: #4a5568;
  padding: 2px 10px;
  border-radius: 20px;
  font-weight: 500;
}

/* ========== 总结 ========== */
.summary-text {
  font-size: 15px;
  line-height: 1.8;
  color: #4a5568;
}

/* ========== 空状态 ========== */
.empty-state {
  padding: 20px;
  text-align: center;
  color: #718096;
  font-size: 14px;
}

/* ========== 风险列表 ========== */
.risk-list, .suggestion-list {
  list-style: none;
}
.risk-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  transition: box-shadow 0.2s;
}
.risk-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.risk-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.severity-badge {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 4px;
  text-transform: uppercase;
}
.risk-category {
  font-size: 13px;
  font-weight: 600;
  color: #4a5568;
  background: #edf2f7;
  padding: 2px 8px;
  border-radius: 4px;
}
.risk-location, .suggestion-location {
  font-size: 13px;
  color: #718096;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  background: #f7fafc;
  padding: 2px 8px;
  border-radius: 4px;
}
.risk-desc {
  font-size: 14px;
  line-height: 1.7;
  color: #4a5568;
  margin-bottom: 8px;
}
.risk-recommendation {
  font-size: 13px;
  color: #2d6a4f;
  background: #f0fff4;
  padding: 10px 14px;
  border-radius: 6px;
  border-left: 3px solid #38a169;
  line-height: 1.6;
}

/* ========== 建议列表 ========== */
.suggestion-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}
.suggestion-header {
  margin-bottom: 8px;
}
.suggestion-desc {
  font-size: 14px;
  line-height: 1.7;
  color: #4a5568;
  margin-bottom: 10px;
}
.code-block-wrapper {
  background: #1a202c;
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
}
.code-block-wrapper pre {
  margin: 0;
}
.code-block-wrapper code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #e2e8f0;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ========== 底部 ========== */
.app-footer {
  text-align: center;
  padding: 32px 0 0;
  color: #a0aec0;
  font-size: 12px;
}

/* ========== 过渡动画 ========== */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

/* ========== 响应式 ========== */
@media (max-width: 600px) {
  .app-wrapper { padding: 12px; }
  .input-row { flex-direction: column; }
  .input-row button { width: 100%; justify-content: center; }
  .app-header h1 { font-size: 22px; }
}
</style>
