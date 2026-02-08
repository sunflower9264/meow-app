package com.miaomiao.assistant.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterCard {
    /**
     * 角色ID
     */
    private String id;

    /**
     * 角色名称
     */
    private String name;

    /**
     * 性格特点
     */
    private String personality;

    /**
     * 说话风格
     */
    private String speakingStyle;

    /**
     * 背景故事
     */
    private String background;
}