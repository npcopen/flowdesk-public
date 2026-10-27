import { computed, ref } from "vue";
import { ticketApi } from "@/api/endpoints";
import { mutationIntent } from "@/lib/mutationIntent";
import type { AsyncProgress as AsyncProgressItem } from "@/types/api";
import type { TicketDetailState } from "./useTicketDetail";

export type TicketActionKind =
  | "assign"
  | "accept"
  | "transfer"
  | "resolve"
  | "close"
  | "reopen";

/** 管理工单操作和评论草稿，在详情重新加载时保留用户输入及结果不确定的重试意图。 */
export function useTicketActions(detail: TicketDetailState) {
  const { ticket, ticketId, candidates, actionError, currentEtag, load } = detail;
  const actionKind = ref<TicketActionKind | null>(null);
  const actionText = ref("");
  const selectedAssignee = ref("");
  const actionLoading = ref(false);
  const commentText = ref("");
  const commentLoading = ref(false);
  const effects = ref<AsyncProgressItem[]>([]);

  const actionTitle = computed(
    () =>
      ({
        assign: "分配工单",
        accept: "接单",
        transfer: "转派工单",
        resolve: "解决工单",
        close: "关闭工单",
        reopen: "重新打开工单",
      })[actionKind.value || "assign"],
  );

  /**
   * 打开指定工单动作表单并重置原因及错误。
   * 需要候选工程师时默认选择首个可用人员。
   */
  function openAction(kind: TicketActionKind): void {
    actionKind.value = kind;
    actionText.value = "";
    actionError.value = null;
    selectedAssignee.value = candidates.value[0]?.userId || "";
  }
  /**
   * 关闭工单动作表单。
   * 提交期间保持表单，避免丢失正在执行的请求及其反馈。
   */
  function closeAction(): void {
    if (!actionLoading.value) actionKind.value = null;
  }
  /**
   * 按选中动作调用分派、接单、转派等工单端点。
   * 携带当前 ETag 和幂等键，成功后保存异步结果并刷新详情。
   */
  async function submitAction(): Promise<void> {
    if (!ticket.value || !actionKind.value) return;
    actionLoading.value = true;
    actionError.value = null;
    try {
      let result;
      // 使用当前详情的版本条件提交，避免操作覆盖其他处理人的并发修改。
      const etag = currentEtag();
      if (actionKind.value === "assign")
        result = await ticketApi.assign(
          ticket.value.ticketId,
          selectedAssignee.value,
          etag,
        );
      if (actionKind.value === "accept")
        result = await ticketApi.accept(ticket.value.ticketId, etag);
      if (actionKind.value === "transfer")
        result = await ticketApi.transfer(
          ticket.value.ticketId,
          { newAssigneeUserId: selectedAssignee.value, reason: actionText.value },
          etag,
        );
      if (actionKind.value === "resolve")
        result = await ticketApi.resolve(
          ticket.value.ticketId,
          actionText.value,
          etag,
        );
      if (actionKind.value === "close")
        result = await ticketApi.close(
          ticket.value.ticketId,
          actionText.value || null,
          etag,
        );
      if (actionKind.value === "reopen")
        result = await ticketApi.reopen(
          ticket.value.ticketId,
          actionText.value,
          etag,
        );
      if (result) {
        effects.value = result.meta.asyncEffects || [];
        actionKind.value = null;
        await load();
      }
    } catch (reason) {
      actionError.value = reason;
    } finally {
      actionLoading.value = false;
    }
  }
  // 评论只有提交成功后才清除幂等意图；网络失败后的同内容重试沿用原键。
  const commentIntent = mutationIntent();
  /**
   * 提交去除首尾空白后的工单评论。
   * 相同载荷重试复用幂等键，仅在成功后清空输入、重置意图并刷新详情。
   */
  async function addComment(): Promise<void> {
    if (!commentText.value.trim()) return;
    commentLoading.value = true;
    actionError.value = null;
    try {
      await ticketApi.addComment(ticketId.value, {
        body: commentText.value.trim(),
      }, commentIntent.key({ticketId: ticketId.value, body: commentText.value.trim()}));
      commentIntent.reset();
      commentText.value = "";
      await load();
    } catch (reason) {
      actionError.value = reason;
    } finally {
      commentLoading.value = false;
    }
  }
  return {
    actionKind, actionTitle, actionText, selectedAssignee, actionLoading,
    commentText, commentLoading, effects,
    openAction, closeAction, submitAction, addComment,
  };
}
