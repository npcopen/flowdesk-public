<script setup lang="ts">
/** 展示评论和时间线，分别反馈读取错误，并向页面发出评论提交事件。 */
import { Clock3, MessageSquare, Send } from "lucide-vue-next";
import ErrorNotice from "@/components/ErrorNotice.vue";
import { formatDate, timelineLabel } from "@/lib/format";
import type { TicketComment, TimelineEntry } from "@/types/api";

defineProps<{
  comments: TicketComment[];
  timeline: TimelineEntry[];
  commentsError: unknown;
  timelineError: unknown;
  canComment: boolean;
  commentLoading: boolean;
  currentUserId?: string;
}>();
const commentText = defineModel<string>("commentText", { required: true });
const emit = defineEmits<{ retry: []; comment: [] }>();
</script>

<template>
  <section class="panel">
    <div class="panel-header">
      <div>
        <h2>评论</h2>
        <p>评论会立即追加到业务时间线。</p>
      </div>
      <MessageSquare :size="18" class="panel-heading-icon" />
    </div>
    <div class="panel-body">
      <ErrorNotice
        v-if="commentsError"
        :error="commentsError"
        @retry="emit('retry')"
      />
      <form
        v-if="canComment"
        class="comment-form"
        @submit.prevent="emit('comment')"
      >
        <textarea
          v-model="commentText"
          required
          maxlength="10000"
          placeholder="写下处理进展或补充信息…"
          aria-label="评论内容"
        />
        <div class="form-actions">
          <button
            class="button button--small"
            type="submit"
            :disabled="commentLoading || !commentText.trim()"
          >
            <Send :size="14" />{{
              commentLoading ? "发送中…" : "添加评论"
            }}
          </button>
        </div>
      </form>
      <div v-else class="muted comment-readonly">
        当前账号只能查看评论，或工单已结束。
      </div>
      <div v-if="!commentsError && comments.length" class="comment-list">
        <article
          v-for="comment in comments"
          :key="comment.commentId"
          class="comment-item"
        >
          <div class="avatar avatar--small">
            {{ comment.authorUserId.slice(0, 1) }}
          </div>
          <div>
            <p>{{ comment.body }}</p>
            <time
              >{{ formatDate(comment.createdAt) }} ·
              {{ comment.authorUserId === currentUserId ? "我" : "处理成员" }}</time
            >
          </div>
        </article>
      </div>
      <div v-else-if="!commentsError" class="muted empty-inline">暂无评论</div>
    </div>
  </section>
  <section class="panel">
    <div class="panel-header">
      <div>
        <h2>业务时间线</h2>
        <p>记录工单的重要处理节点。</p>
      </div>
      <Clock3 :size="18" class="panel-heading-icon" />
    </div>
    <ErrorNotice
      v-if="timelineError"
      :error="timelineError"
      @retry="emit('retry')"
    />
    <div v-if="!timelineError" class="timeline-list">
      <div
        v-for="entry in timeline"
        :key="entry.timelineId"
        class="timeline-item"
      >
        <span class="timeline-dot" />
        <div>
          <strong>{{ entry.summary }}</strong
          ><span
            >{{ timelineLabel(entry.eventType) }} ·
            {{ formatDate(entry.occurredAt) }}</span
          >
        </div>
      </div>
      <div v-if="!timeline.length" class="muted empty-inline">
        暂无时间线记录
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel-heading-icon {
  color: var(--teal);
}
.comment-form textarea {
  width: 100%;
  min-height: 92px;
  padding: 9px 10px;
  border: 1px solid #cbd9dc;
  border-radius: 6px;
  resize: vertical;
  font-size: 13px;
}
.comment-form textarea:focus {
  border-color: #4da89f;
  outline: none;
  box-shadow: 0 0 0 3px rgba(77, 168, 159, 0.15);
}
.comment-form .form-actions {
  margin-top: 9px;
}
.comment-readonly {
  padding: 8px 0 17px;
  font-size: 12px;
}
.comment-list {
  display: grid;
  gap: 13px;
  margin-top: 17px;
  padding-top: 15px;
  border-top: 1px solid var(--line);
}
.comment-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}
.avatar--small {
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  font-size: 11px;
}
.comment-item p {
  margin-bottom: 5px;
  color: #3d4d56;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.comment-item time {
  color: var(--muted);
  font-size: 10px;
}
.empty-inline {
  padding: 20px 0 3px;
  font-size: 12px;
}
.timeline-list {
  padding: 19px 21px 21px;
}
.timeline-item {
  position: relative;
  display: flex;
  gap: 12px;
  min-height: 52px;
}
.timeline-item:not(:last-child)::before {
  position: absolute;
  top: 14px;
  left: 4px;
  bottom: -8px;
  width: 1px;
  background: #d8e5e5;
  content: "";
}
.timeline-dot {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  flex: 0 0 9px;
  margin-top: 4px;
  border: 2px solid #8bc9c1;
  border-radius: 50%;
  background: #fff;
}
.timeline-item strong,
.timeline-item span {
  display: block;
}
.timeline-item strong {
  color: #3e5058;
  font-size: 12px;
}
.timeline-item span {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
}
@media (max-width: 620px) {
  .timeline-list {
    padding: 16px;
  }
}
</style>
