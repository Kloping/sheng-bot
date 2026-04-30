package top.kloping.code.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.kloping.code.entity.MessageRecord;

/**
 * 消息记录 Mapper。
 * 负责 message_record 表的基础数据库访问。
 */
@Mapper
public interface MessageRecordMapper extends BaseMapper<MessageRecord> {
}
