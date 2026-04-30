package top.kloping.code.service;

import top.kloping.code.entity.MessageRecord;

import java.util.List;

/**
 * 消息格式化服务接口。
 * 用于把原始消息记录转换为适合阅读和向量化的文本格式。
 */
public interface MessageFormatService {

    /**
     * 将消息列表格式化为可读文本。
     * 同一发送人在配置时间窗口内的连续消息会自动拼接。
     *
     * @param records 原始消息记录列表，允许为空
     * @return 格式化后的文本内容
     */
    String formatConversationMessages(List<MessageRecord> records);
}
