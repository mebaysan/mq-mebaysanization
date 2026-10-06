import {
  AlertCircle,
  ArrowLeft,
  Check,
  CheckCircle2,
  ChevronRight,
  Copy,
  Info,
  Plus,
  RefreshCw,
  Search,
  Star,
  Trash2,
  X,
  type LucideIcon,
  type LucideProps,
} from 'lucide-react'

/**
 * The app's icons, drawn from Lucide.
 *
 * <p>Re-exported under stable local names (…Icon) behind a thin wrapper that sets a slightly lighter
 * default stroke (1.75 vs Lucide's 2) and a 16px default size, matching the refined weight of the rest
 * of the UI. Every prop still forwards, so a call site can override size, strokeWidth, className or
 * colour. The indirection is deliberate: it keeps the icon set swappable from one file, and because
 * Lucide is fully tree-shakeable, only the icons imported here land in the bundle.
 */
function icon(Base: LucideIcon) {
  return function Icon({ size = 16, strokeWidth = 1.75, ...props }: LucideProps) {
    return <Base size={size} strokeWidth={strokeWidth} {...props} />
  }
}

export const AlertIcon = icon(AlertCircle)
export const ArrowLeftIcon = icon(ArrowLeft)
export const CheckIcon = icon(Check)
export const CheckCircleIcon = icon(CheckCircle2)
export const ChevronRightIcon = icon(ChevronRight)
export const CopyIcon = icon(Copy)
export const InfoIcon = icon(Info)
export const PlusIcon = icon(Plus)
export const RefreshIcon = icon(RefreshCw)
export const SearchIcon = icon(Search)
// Pass fill="currentColor" to render a favorited (filled) star; the outline is the default.
export const StarIcon = icon(Star)
export const TrashIcon = icon(Trash2)
export const XIcon = icon(X)
