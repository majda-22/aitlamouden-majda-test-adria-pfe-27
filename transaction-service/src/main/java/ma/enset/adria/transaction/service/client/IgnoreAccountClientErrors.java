package ma.enset.adria.transaction.service.client;

import feign.FeignException;
import java.util.function.Predicate;

/** Les refus HTTP 4xx n'indiquent pas une panne du service bancaire. */
public class IgnoreAccountClientErrors implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FeignException feign && feign.status() >= 400 && feign.status() < 500) return true;
        }
        return false;
    }
}
