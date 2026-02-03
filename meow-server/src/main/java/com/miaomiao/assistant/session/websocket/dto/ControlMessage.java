package com.miaomiao.assistant.session.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Control message for session management
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ControlMessage extends WSMessage {
    private String action;       // start, stop, abort, config
    private String config;       // Configuration data
}
