import { Command } from 'cmdk'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'

import { useConnections } from '../api/connections'
import { DialogTitle, Modal } from './Modal'
import { ProviderBadge } from './Primitives'
import { PlusIcon, SearchIcon } from './icons'

/**
 * A ⌘K / Ctrl-K command palette for jumping around without the mouse.
 *
 * <p>Mounted once, globally. It opens on the shortcut or on a {@code command-palette:open} window event
 * (the header button dispatches that), lists every saved connection to open directly, and offers the
 * top-level pages. cmdk handles the fuzzy filtering and arrow-key selection; Radix (via {@link Modal})
 * handles the focus trap and Escape. Closing unmounts the list, so the query resets for next time.
 */
export function CommandPalette() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const connections = useConnections()

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setOpen((value) => !value)
      }
    }
    const onOpen = () => setOpen(true)
    window.addEventListener('keydown', onKey)
    window.addEventListener('command-palette:open', onOpen)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('command-palette:open', onOpen)
    }
  }, [])

  const go = (path: string) => {
    setOpen(false)
    void navigate(path)
  }

  const itemClass =
    'flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-fg-muted ' +
    'aria-selected:bg-brand-50 aria-selected:text-brand-900'

  return (
    <Modal open={open} onOpenChange={setOpen} align="start" contentClassName="max-w-xl overflow-hidden">
      <DialogTitle className="sr-only">Command menu</DialogTitle>
      <Command
        label="Command menu"
        className="[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:pt-3 [&_[cmdk-group-heading]]:text-[11px] [&_[cmdk-group-heading]]:font-semibold [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wide [&_[cmdk-group-heading]]:text-fg-subtle"
      >
        <div className="flex items-center gap-2 border-b border-line px-3">
          <SearchIcon size={16} className="shrink-0 text-fg-subtle" />
          <Command.Input
            autoFocus
            placeholder="Jump to a connection or page…"
            className="h-12 w-full bg-transparent text-sm text-fg placeholder:text-fg-subtle focus:outline-none"
          />
        </div>
        <Command.List className="max-h-[min(60vh,24rem)] overflow-auto p-2">
          <Command.Empty className="px-3 py-6 text-center text-sm text-fg-subtle">
            No matches.
          </Command.Empty>

          {(connections.data ?? []).length > 0 && (
            <Command.Group heading="Connections">
              {(connections.data ?? []).map((profile) => (
                <Command.Item
                  key={profile.id}
                  // A stable value cmdk filters on: name plus provider, so typing either finds it.
                  value={`${profile.name} ${profile.providerLabel}`}
                  onSelect={() => go(`/connections/${profile.id}/queue`)}
                  className={itemClass}
                >
                  <span className="min-w-0 flex-1 truncate font-medium">{profile.name}</span>
                  <ProviderBadge provider={profile.provider} label={profile.providerLabel} />
                </Command.Item>
              ))}
            </Command.Group>
          )}

          <Command.Group heading="Go to">
            <Command.Item value="connections all brokers" onSelect={() => go('/connections')} className={itemClass}>
              Connections
            </Command.Item>
            <Command.Item value="logs" onSelect={() => go('/logs')} className={itemClass}>
              Logs
            </Command.Item>
            <Command.Item value="manual help docs" onSelect={() => go('/manual')} className={itemClass}>
              Manual
            </Command.Item>
            <Command.Item
              value="new connection add broker"
              onSelect={() => go('/connections/new')}
              className={itemClass}
            >
              <PlusIcon size={15} className="text-fg-subtle" />
              New connection
            </Command.Item>
          </Command.Group>
        </Command.List>
      </Command>
    </Modal>
  )
}
