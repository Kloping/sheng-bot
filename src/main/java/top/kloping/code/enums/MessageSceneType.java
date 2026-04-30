package top.kloping.code.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消息场景类型枚举。
 * 用于区分消息来源所属的会话场景。
 */
@Getter
@RequiredArgsConstructor
public enum MessageSceneType {

    GROUP("GROUP");

    private final String code;
}
