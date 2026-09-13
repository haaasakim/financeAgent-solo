package org.haaasaki.financeagent.data.model;

/**
 * 选股策略（{@code strategy} 表）。
 *
 * <p>{@code dsl} 是条件树 JSON，格式见 docs/work-items/WI-004-选股模块.md 第 2.2 节。
 *
 * @param id          自增主键
 * @param name        策略名，唯一；重名保存视为覆盖更新
 * @param description 说明，可为 null
 * @param dsl         条件树 JSON 文本
 */
public record Strategy(
        long id,
        String name,
        String description,
        String dsl) {
}
