package cc.fplj.magpiemobile.core

/**
 * 同步的聊天轮次持久化边界；调用方在网络请求前 begin，只在完整成功回复后 complete。
 *
 * begin 返回前必须持久化 pending user，并返回该轮次的稳定标识。
 * complete 必须原子保存 assistant 并把本轮标为成功；返回后会话才加入内存 assistant。
 * fail 只接收固定分类/部分长度的 error 摘要，不得保存失败正文、partial 或原始异常。
 * 恢复上下文由实现方仅提供完整成功的 user/assistant 对，pending/failed 轮次不得参与。
 * Room 消费者只传此接口并将 StreamingChatSession 的旧 JSONL store 设为 null。
 */
public interface ChatTurnStore {
    fun begin(user: SessionRecord): String
    fun complete(turnId: String, assistant: SessionRecord)
    fun fail(turnId: String, failure: SessionRecord)
}
