package dev.retryx.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of results.
 *
 * <p>Offset pagination, chosen deliberately and with its limits known. It gives the dashboard a
 * total count and the ability to jump to page 7, which is what a human browsing failures wants. The
 * cost is that {@code OFFSET 10000} makes Postgres walk and discard ten thousand rows, so deep
 * pages get slower, and a row inserted while you read page 1 shifts page 2 by one.
 *
 * <p>Keyset pagination ({@code WHERE created_at < :last ORDER BY created_at DESC LIMIT n}) has
 * neither problem and is the right answer for an API a machine consumes — but it cannot offer a
 * total or random access. For a three-page dashboard over a per-owner dataset, offset is the better
 * trade; the switch is noted as future work rather than presented as unnecessary.
 *
 * @param items      the page's contents
 * @param page       zero-based page number
 * @param size       page size
 * @param total      total matching rows
 * @param totalPages number of pages at this size
 * @param <T>        item type
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {

    /**
     * Converts a Spring Data page, mapping each element.
     *
     * @param page   the source page
     * @param mapper entity → DTO
     * @param <E>    entity type
     * @param <T>    DTO type
     * @return the API representation
     */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Wraps an already-mapped list.
     *
     * @param items  mapped contents
     * @param page   zero-based page number
     * @param size   page size
     * @param total  total matching rows
     * @param <T>    item type
     * @return the API representation
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }
}
