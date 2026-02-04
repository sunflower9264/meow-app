<template>
  <div class="home">
    <!-- Header with minimal branding -->
    <header class="header">
      <h1 class="logo">喵</h1>
    </header>

    <!-- Search Section -->
    <section class="search-section">
      <div class="search-wrapper">
        <input
          v-model="searchQuery"
          type="text"
          placeholder="搜索对话..."
          class="search-input"
          @input="filterConversations"
        />
        <span class="search-icon">🔍</span>
      </div>
    </section>

    <!-- New Conversation Button -->
    <section class="action-section">
      <button @click="createNewConversation" class="new-chat-btn">
        <span class="btn-icon">+</span>
        <span class="btn-text">新增对话</span>
      </button>
    </section>

    <!-- Conversation List -->
    <section class="conversations-section">
      <div v-if="filteredConversations.length === 0" class="empty-state">
        <span class="empty-icon">🐱</span>
        <p>{{ searchQuery ? '未找到匹配的对话' : '暂无对话记录' }}</p>
      </div>

      <div v-else class="conversation-list">
        <div
          v-for="conv in filteredConversations"
          :key="conv.id"
          @click="openConversation(conv.id)"
          class="conversation-card"
        >
          <div class="card-header">
            <h3 class="card-title">{{ conv.title || '未命名对话' }}</h3>
            <span class="card-time">{{ formatTime(conv.updatedAt) }}</span>
          </div>
          <p class="card-preview">{{ conv.preview || '暂无预览' }}</p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// State
const searchQuery = ref('')

// Mock conversation data - will be replaced with API call
const conversations = ref([
  {
    id: '1',
    title: '今天天气如何',
    preview: '询问今天的天气情况和温度',
    updatedAt: Date.now() - 1000 * 60 * 30 // 30 minutes ago
  },
  {
    id: '2',
    title: 'JavaScript 闭包问题',
    preview: '讨论 JavaScript 中闭包的工作原理',
    updatedAt: Date.now() - 1000 * 60 * 60 * 2 // 2 hours ago
  },
  {
    id: '3',
    title: '推荐一些学习资源',
    preview: '请求推荐前端开发学习资源',
    updatedAt: Date.now() - 1000 * 60 * 60 * 24 // 1 day ago
  }
])

// Computed
const filteredConversations = computed(() => {
  if (!searchQuery.value.trim()) {
    return conversations.value
  }
  const query = searchQuery.value.toLowerCase()
  return conversations.value.filter(conv =>
    conv.title.toLowerCase().includes(query) ||
    conv.preview.toLowerCase().includes(query)
  )
})

// Methods
function filterConversations() {
  // Filter logic handled by computed property
}

function createNewConversation() {
  router.push('/chat/new')
}

function openConversation(id) {
  router.push(`/chat/${id}`)
}

function formatTime(timestamp) {
  const now = Date.now()
  const diff = now - timestamp
  const minutes = Math.floor(diff / (1000 * 60))
  const hours = Math.floor(diff / (1000 * 60 * 60))
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`

  const date = new Date(timestamp)
  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
</script>

<style>
/*
 * DESIGN DIRECTION: Zen Minimalism / 禅意极简
 *
 * Aesthetic: Japanese-inspired minimalism with extreme restraint
 * Differentiation: Uses generous whitespace, subtle micro-interactions,
 * and a single-character logo instead of typical icons
 *
 * Typography: Noto Serif SC for logo, system fonts for body
 * Color: Warm white base with soft gray accents
 * Motion: Gentle hover transitions with scale effects
 */

@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600;700&display=swap');
</style>

<style scoped>
.home {
  min-height: 100vh;
  background: #faf9f7;
  display: flex;
  flex-direction: column;
}

/* Header - Extreme minimalism */
.header {
  padding: 32px 24px 16px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.logo {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  font-weight: 700;
  color: #1a1a1a;
  margin: 0;
  letter-spacing: 0.1em;
}

/* Search Section */
.search-section {
  padding: 16px 24px;
}

.search-wrapper {
  position: relative;
  max-width: 600px;
  margin: 0 auto;
}

.search-input {
  width: 100%;
  height: 48px;
  padding: 0 48px 0 20px;
  background: #ffffff;
  border: 1px solid #e8e6e3;
  border-radius: 24px;
  font-size: 0.95rem;
  font-family: inherit;
  color: #1a1a1a;
  outline: none;
  transition: all 0.2s ease;
}

.search-input::placeholder {
  color: #a0a0a0;
}

.search-input:focus {
  border-color: #1a1a1a;
  box-shadow: 0 0 0 3px rgba(26, 26, 26, 0.05);
}

.search-icon {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 1.1rem;
  pointer-events: none;
  opacity: 0.4;
}

/* Action Section */
.action-section {
  padding: 16px 24px;
  display: flex;
  justify-content: center;
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 48px;
  padding: 0 32px;
  background: #1a1a1a;
  color: #ffffff;
  border: none;
  border-radius: 24px;
  font-size: 0.95rem;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.new-chat-btn:hover {
  background: #333333;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.new-chat-btn:active {
  transform: translateY(0);
}

.btn-icon {
  font-size: 1.2rem;
  line-height: 1;
}

.btn-text {
  letter-spacing: 0.05em;
}

/* Conversations Section */
.conversations-section {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

.conversations-section::-webkit-scrollbar {
  width: 4px;
}

.conversations-section::-webkit-scrollbar-track {
  background: transparent;
}

.conversations-section::-webkit-scrollbar-thumb {
  background: #e0dedb;
  border-radius: 2px;
}

/* Empty State */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  color: #a0a0a0;
  text-align: center;
}

.empty-icon {
  font-size: 3rem;
  opacity: 0.5;
  margin-bottom: 16px;
}

.empty-state p {
  font-size: 0.9rem;
  letter-spacing: 0.05em;
}

/* Conversation List */
.conversation-list {
  display: grid;
  gap: 12px;
  max-width: 800px;
  margin: 0 auto;
}

/* Conversation Card */
.conversation-card {
  background: #ffffff;
  border: 1px solid #e8e6e3;
  border-radius: 16px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.conversation-card:hover {
  border-color: #1a1a1a;
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.06);
}

.conversation-card:active {
  transform: translateY(0);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 8px;
}

.card-title {
  font-size: 1rem;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-time {
  font-size: 0.75rem;
  color: #a0a0a0;
  white-space: nowrap;
  letter-spacing: 0.02em;
}

.card-preview {
  font-size: 0.85rem;
  color: #666666;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.5;
}

/* Responsive */
@media (max-width: 480px) {
  .header {
    padding: 24px 20px 12px;
  }

  .logo {
    font-size: 1.75rem;
  }

  .search-section,
  .action-section,
  .conversations-section {
    padding-left: 20px;
    padding-right: 20px;
  }

  .search-input {
    height: 44px;
    border-radius: 22px;
  }

  .new-chat-btn {
    height: 44px;
    padding: 0 24px;
    border-radius: 22px;
  }

  .conversation-card {
    padding: 16px;
    border-radius: 14px;
  }
}
</style>
