package com.wanghongbo.renewalagent.persistence;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JdbcAgentDataGateway implements AgentDataGateway {

    private final DatabaseService database;

    public JdbcAgentDataGateway(DatabaseService database) {
        this.database = database;
    }

    @Override
    public Optional<Account> findAccount(String accountId) {
        return database.findAccount(accountId);
    }

    @Override
    public List<UsagePoint> findUsage(String accountId, LocalDate from) {
        return database.findUsage(accountId, from);
    }

    @Override
    public List<Ticket> findOpenTickets(String accountId) {
        return database.findOpenTickets(accountId);
    }

    @Override
    public List<Invoice> findInvoices(String accountId) {
        return database.findInvoices(accountId);
    }
}
