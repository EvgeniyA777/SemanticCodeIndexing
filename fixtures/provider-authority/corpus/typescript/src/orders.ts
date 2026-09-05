import { Validator } from './validator';

/**
 * Protected Stage 0 fixture (plans/018).
 *
 * Definitions + calls source for the TypeScript lane. `index.ts` re-exports
 * from this module, so the re-export identity fixtures can prove that a
 * re-exported symbol normalizes to the SAME CanonicalFactKey as its origin
 * definition here.
 */
export function createOrder(id: string): string {
  return normalize(id);
}

export function normalize(value: string): string {
  return value.trim();
}

export class OrderService {
  private validator: Validator;

  constructor(validator: Validator) {
    this.validator = validator;
  }

  handle(order: string): string {
    return createOrder(order);
  }
}
