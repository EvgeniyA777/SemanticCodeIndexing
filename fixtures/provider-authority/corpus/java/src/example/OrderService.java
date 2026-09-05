package example;

import java.util.List;
import example.Validator;

/**
 * Protected Stage 0 fixture (plans/018).
 *
 * Exercises definitions, calls, a constructor, and two same-name Java method
 * overloads that must remain DISTINCT canonical facts:
 *   handle(String)        -> arity 1
 *   handle(String, int)   -> arity 2
 *
 * The provider-neutral CanonicalFactKey contract must keep these two overloads
 * separate while collapsing regex / tree-sitter / SCIP / LSP spellings of the
 * SAME overload into one key. See fixtures/provider-authority/identity/.
 */
public class OrderService {
    private final Validator validator;

    public OrderService(Validator validator) {
        this.validator = validator;
    }

    public String handle(String order) {
        return validator.validate(order);
    }

    public String handle(String order, int retries) {
        String result = validator.validate(order);
        return result;
    }

    public List<String> handleAll(List<String> orders) {
        return orders;
    }
}
