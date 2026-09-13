package org.haaasaki.financeagent.screener.dsl;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 选股条件树。
 *
 * <p>组语义：
 * <ul>
 *   <li>{@code all}：全部满足（AND）</li>
 *   <li>{@code any}：任一满足（OR）</li>
 *   <li>{@code not}：排除同时满足该组全部条件的标的（{@code AND NOT (c1 AND c2 ...)}）</li>
 *   <li>空组不参与</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenerDsl(
        List<Condition> all,
        List<Condition> any,
        List<Condition> not) {

    public ScreenerDsl {
        all = all == null ? List.of() : List.copyOf(all);
        any = any == null ? List.of() : List.copyOf(any);
        not = not == null ? List.of() : List.copyOf(not);
    }

    public static ScreenerDsl empty() {
        return new ScreenerDsl(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return all.isEmpty() && any.isEmpty() && not.isEmpty();
    }
}
