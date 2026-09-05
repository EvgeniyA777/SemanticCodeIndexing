package example;

/**
 * Protected Stage 0 fixture (plans/018).
 *
 * Reference target for OrderService. `validate(String)` is the callee that
 * OrderService#handle overloads invoke, so cross-provider references and
 * callers/callees baselines can be anchored on a single stable definition.
 */
public class Validator {

    public String validate(String order) {
        return order.trim();
    }
}
