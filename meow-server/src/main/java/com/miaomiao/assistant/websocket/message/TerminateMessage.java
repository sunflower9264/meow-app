package com.miaomiao.assistant.websocket.message;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Explicit terminate request from client.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TerminateMessage extends WSMessage {
}
