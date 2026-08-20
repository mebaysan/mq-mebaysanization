import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Compose class names, resolving Tailwind conflicts so the last utility wins.
 *
 * <p>{@code clsx} flattens conditionals and arrays; {@code tailwind-merge} then dedupes conflicting
 * utilities (a later {@code px-4} beats an earlier {@code px-2}) so a caller can override a base class
 * without the two fighting in the DOM. Used wherever a base class string is extended per call site.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
