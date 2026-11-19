package com.wanghongbo.renewalagent.evaluation;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import com.wanghongbo.renewalagent.persistence.AgentDataGateway;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record EvaluationFixture(
        String version,
        String hash,
        LocalDate referenceDate,
        Map<String, Account> accounts,
        Map<String, List<UsagePoint>> usage,
        Map<String, List<Ticket>> tickets,
        Map<String, List<Invoice>> invoices
) {
    public AgentDataGateway gateway() {
        return new AgentDataGateway() {
            @Override
            public Optional<Account> findAccount(String accountId) {
                return Optional.ofNullable(accounts.get(accountId));
            }

            @Override
            public List<UsagePoint> findUsage(String accountId, LocalDate from) {
                return usage.getOrDefault(accountId, List.of()).stream()
                        .filter(point -> !point.date().isBefore(from))
                        .toList();
            }

            @Override
            public List<Ticket> findOpenTickets(String accountId) {
                return tickets.getOrDefault(accountId, List.of()).stream()
                        .filter(ticket -> !"RESOLVED".equals(ticket.status()))
                        .toList();
            }

            @Override
            public List<Invoice> findInvoices(String accountId) {
                return invoices.getOrDefault(accountId, List.of());
            }
        };
    }
}
