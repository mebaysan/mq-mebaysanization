import type { ReactNode } from 'react'
import { Toaster, toast } from 'sonner'

interface ToastApi {
  success: (message: string) => void
  error: (message: string) => void
  info: (message: string) => void
}

/**
 * Toasts, on Sonner.
 *
 * <p>The {@code useToast()} shape is kept exactly as it was — {@code success/error/info} — so every call
 * site is unchanged; only the engine underneath swapped to Sonner, which brings stacking, swipe-to-
 * dismiss, an accessible live region and a close button for free. Errors linger longer than the rest
 * because they usually carry a broker message worth reading.
 */
const api: ToastApi = {
  success: (message) => void toast.success(message),
  error: (message) => void toast.error(message, { duration: 9000 }),
  info: (message) => void toast.info(message),
}

export function useToast(): ToastApi {
  return api
}

export function ToastProvider({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <Toaster
        position="bottom-right"
        richColors
        closeButton
        // Follow the OS light/dark setting, the same signal the rest of the theme reads.
        theme="system"
        toastOptions={{
          // Match the app's softer radius and elevation rather than Sonner's defaults.
          className: 'rounded-xl',
        }}
      />
    </>
  )
}
