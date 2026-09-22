package com.jevguard.domain;

/**
 * 路由动作。语义按 direction 分叉（PRD §4.5.2）：
 * deposit  AUTO_PASS=正常入账 / MANUAL_REVIEW=入账但账户标记待审 / FREEZE=入账但冻结等额余额
 * withdraw AUTO_PASS=放行广播 / MANUAL_REVIEW=挂起出账资金不离开 / FREEZE=拒绝出账退回余额
 */
public enum Action {
    AUTO_PASS, MANUAL_REVIEW, FREEZE
}
