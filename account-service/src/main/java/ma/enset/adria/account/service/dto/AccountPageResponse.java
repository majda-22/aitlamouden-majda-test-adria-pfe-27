package ma.enset.adria.account.service.dto;

import java.util.List;

public record AccountPageResponse(
        List<AccountResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
