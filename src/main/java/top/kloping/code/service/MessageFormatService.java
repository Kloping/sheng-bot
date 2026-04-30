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

    /**
     * 计算格式化文本的有效内容长度，用于向量化分段阈值判断。
     * 每个图片占位符 [图片:md5] 按 25 字计入，其余文本按实际字数计入。
     *
     * @param formattedContent 格式化后的文本内容，允许为空
     * @return 有效内容长度
     */
    int effectiveContentLength(String formattedContent);
}
