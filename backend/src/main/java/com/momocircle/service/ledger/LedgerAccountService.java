package com.momocircle.service.ledger;

import com.momocircle.domain.ledger.LedgerAccountEntity;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.repository.ledger.LedgerAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Creates ledger accounts. Creation races are resolved by the database: the insert runs in its own short transaction, a
 * unique-constraint loss is expected and harmless, and the winner's row is then re-read.
 *
 * <p>Wallet ownership: {@code ownerUserId} MUST come from the authenticated server-side principal, never from a request.
 */
@Service
public class LedgerAccountService {

    private final LedgerAccountRepository accounts;
    private final TransactionTemplate requiresNew;

    @PersistenceContext
    private EntityManager em;

    public LedgerAccountService(LedgerAccountRepository accounts, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The user's wallet in {@code currency}, creating it on first use. */
    public Account getOrCreateWallet(String ownerUserId, String currency) {
        requireCurrency(currency);
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new LedgerException(Code.NOT_OWNER, "A wallet needs an authenticated owner");
        }
        return accounts.findWallet(ownerUserId, currency, Account.Kind.USER_WALLET)
                .or(() -> {
                    insertQuietly(LedgerAccountEntity.wallet(UUID.randomUUID().toString(), ownerUserId, currency, Instant.now()));
                    return accounts.findWallet(ownerUserId, currency, Account.Kind.USER_WALLET);
                })
                .map(LedgerAccountEntity::toDomain)
                .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Wallet could not be created"));
    }

    /**
     * The business's wallet in {@code currency}, creating it on first use. The caller (BusinessWalletAccessService) has
     * already verified that the business exists; a foreign-key failure on a non-existent business surfaces as not found.
     */
    public Account getOrCreateBusinessWallet(String businessId, String currency) {
        requireCurrency(currency);
        if (businessId == null || businessId.isBlank()) {
            throw new LedgerException(Code.NOT_OWNER, "A business wallet needs a business");
        }
        return accounts.findBusinessWallet(businessId, currency, Account.Kind.BUSINESS_WALLET)
                .or(() -> {
                    insertQuietly(LedgerAccountEntity.businessWallet(UUID.randomUUID().toString(), businessId, currency, Instant.now()));
                    return accounts.findBusinessWallet(businessId, currency, Account.Kind.BUSINESS_WALLET);
                })
                .map(LedgerAccountEntity::toDomain)
                .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "Business wallet could not be created"));
    }

    /** The system DEMO_FUNDING or WITHDRAWAL_CLEARING account for {@code currency}, creating it if missing. */
    public Account ensureSystemAccount(Account.Kind kind, String currency) {
        requireCurrency(currency);
        if (kind == Account.Kind.USER_WALLET || kind == Account.Kind.BUSINESS_WALLET) {
            throw new LedgerException(Code.ACCOUNT_NOT_FOUND, "Not a system account kind");
        }
        return accounts.findSystemAccount(kind, currency)
                .or(() -> {
                    insertQuietly(LedgerAccountEntity.system(UUID.randomUUID().toString(), kind, currency, Instant.now()));
                    return accounts.findSystemAccount(kind, currency);
                })
                .map(LedgerAccountEntity::toDomain)
                .orElseThrow(() -> new LedgerException(Code.ACCOUNT_NOT_FOUND, "System account could not be created"));
    }

    private void insertQuietly(LedgerAccountEntity e) {
        try {
            requiresNew.execute(status -> {
                em.persist(e);
                em.flush();
                return null;
            });
        } catch (RuntimeException lostRaceOrInvalidOwner) {
            // Either a concurrent creator won the unique constraint (the caller re-reads and finds its row), or the user
            // does not exist (foreign key). In the latter case the re-read is empty and the caller reports it.
        }
    }

    private static void requireCurrency(String currency) {
        try {
            if (currency == null || !currency.equals(currency.toUpperCase()) || Currency.getInstance(currency).getDefaultFractionDigits() < 0) {
                throw new LedgerException(Code.INVALID_CURRENCY, "Unsupported currency");
            }
        } catch (IllegalArgumentException ex) {
            throw new LedgerException(Code.INVALID_CURRENCY, "Unknown currency");
        }
    }
}
