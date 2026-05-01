package top.kloping.code.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃对话跟踪器。
 * 当机器人被 @并回复后，跟踪该群的活跃对话状态，
 * 在后续 2~5 条消息（30分钟内）中自主判断是否需要继续回复。
 */
@Slf4j
@Component
public class ActiveConversationTracker {

    /** 每次激活后最大自主判断消息条数。 */
    private static final int MAX_AUTONOMOUS_MESSAGES = 3;

    /** 活跃窗口时长（分钟）。 */
    private static final long WINDOW_MINUTES = 30;

    private final ConcurrentHashMap<Long, ActiveState> states = new ConcurrentHashMap<>();

    /**
     * 激活指定群的活跃对话状态（机器人回复后调用），重置计数器和计时。
     *
     * @param groupId 群ID
     */
    public void activate(long groupId) {
        states.put(groupId, new ActiveState(System.currentTimeMillis(), 0));
        log.debug("活跃对话已激活, groupId={}", groupId);
    }

    /**
     * 判断指定群是否处于活跃对话窗口内，且消息条数未超过上限。
     * 超时或超数时自动清除状态。
     *
     * @param groupId 群ID
     * @return 是否应进行自主判断
     */
    public boolean shouldCheckAutonomous(long groupId) {
        ActiveState state = states.get(groupId);
        if (state == null) {
            return false;
        }
        long elapsedMinutes = Duration.ofMillis(System.currentTimeMillis() - state.lastReplyEpochMillis).toMinutes();
        if (elapsedMinutes >= WINDOW_MINUTES) {
            states.remove(groupId);
            log.debug("活跃对话已超时, groupId={}", groupId);
            return false;
        }
        if (state.messageCount >= MAX_AUTONOMOUS_MESSAGES) {
            states.remove(groupId);
            log.debug("活跃对话消息数已达上限, groupId={}", groupId);
            return false;
        }
        return true;
    }

    /**
     * 记录自主判断后未回复，递增消息计数。
     *
     * @param groupId 群ID
     */
    public void incrementNoReply(long groupId) {
        ActiveState state = states.get(groupId);
        if (state != null) {
            state.messageCount++;
            log.debug("活跃对话未回复计数+1, groupId={}, count={}", groupId, state.messageCount);
        }
    }

    /**
     * 活跃对话状态，记录上次回复时间和未回复消息计数。
     */
    private static class ActiveState {
        volatile long lastReplyEpochMillis;
        volatile int messageCount;

        ActiveState(long lastReplyEpochMillis, int messageCount) {
            this.lastReplyEpochMillis = lastReplyEpochMillis;
            this.messageCount = messageCount;
        }
    }
}
