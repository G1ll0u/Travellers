# GUIs

- Every Travellers screen implements the empty marker `ITravellersGui` (toasts are drawn over those only). `GuiTravellersButton` + `TravellersGuiTheme` are the standard button and theme, meant to replace remaining vanilla `GuiButton`s. `GuiContextMenu` is the reusable right-click menu.
- **Never load sounds synchronously in `initGui()`** (freezes the client). A `GuiTextField` must be drawn every frame. `Gui.drawRect` leaves the GL colour set — reset to white before drawing an entity.

## Worker Hub (`GuiWorkerHub`, `hub/`)
The single entry point for a hired worker: live portrait with the menu fanned out as a tree. Rationale: memory `project_worker_hub_gui`.
- `WorkerHubMenu` is the only file to touch to reorganise the menu (list order = display order, nesting = nesting). `WorkerHubNode` = entry, `WorkerHubContext` = what actions see, `WorkerHubLayout` = placement. Sizes come from `TravellersConfig.WORKER_HUB`.
- `fitsWithin` is tested on a fully-expanded layout; failing it falls back to single-branch.
- `WorkerHubNavState` cannot live on the screen (sub-screens are opened server-side); clear it on `GuiOpenEvent` with a null screen, never in `onGuiClosed`.
- Both hub-opening paths send `MsgSyncWorkerHireState` and `WorkerGroupService.syncLeaderFollowerCount`.
- Portrait (`WorkerPortraitRenderer`) renders the real entity: only the head moves (pin body yaw and every `prev*`), yaw mirrored, scale divided by the entity's render scale.

## Formation Hub (`formation/GuiWorkerFormationHub`)
Leader list | worldmap | group card | journal. Left click selects, right drag pans, a still right click opens the orders menu built from selection × what is under the cursor. **The menu adds no server logic** — every order goes through the existing group/mission messages. On-duty workers are never selectable. Never use `drawMapFrame` there.

## Player mark
`WorkerOwnerVisualsSavedData` stores colour + armband style per owner; the owner's own colour is synced to `ClientOwnerVisualsCache`, which every worldmap GUI reads.
