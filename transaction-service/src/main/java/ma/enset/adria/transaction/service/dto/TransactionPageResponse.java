package ma.enset.adria.transaction.service.dto;

import java.util.List;

public record TransactionPageResponse(List<TransactionResponse> content, int page, int size,
                                      long totalElements, int totalPages) {
}
