package com.wanghongbo.renewalagent.persistence;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AgentDataGateway {

    Optional<Account> findAccount(String accountId);

    List<UsagePoint> findUsage(String accountId, LocalDate from);

    List<Ticket> findOpenTickets(String accountId);

    List<Invoice> findInvoices(String accountId);
}
