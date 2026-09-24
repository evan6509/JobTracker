# JobTracker product specification

## Purpose

JobTracker helps one person keep current and future jobs organized. A job holds the people, location, schedule, work details, outside workers, and reference photos needed on site. The home screen shows job priority at a glance; details stay one tap away.

This specification describes the Android app and its current behavior.

## Terms and job states

- **Planned:** A future or inactive job. There may be any number of planned jobs.
- **Active:** The job currently being worked. At most one job may be active at a time.
- **Completed:** A finished job, retained for reference but removed from the active/planned home list.
- **Priority order:** The order of job tabs on the home screen. The user controls it by dragging; state and priority are separate.

Creating a job does not automatically make it active. After setup, if no job is active, the app asks whether to activate the new job. If one is already active, the new job stays planned. Activating a planned job while another is active requires a clear confirmation that moves the previous active job back to planned.

## Main screens and flows

### 1. Home

When there are no active or planned jobs, show gray text reading exactly **“no jobs planned”** and a **“New Job”** button directly below it. Completed jobs do not prevent this empty state. A **Scan job QR** action is available from Home, including the empty state.

When jobs exist, display a series of cards from the top to the bottom of the screen, allowing for scrolling if there are many. Each card shows a short job identifier (such as the client name or job title), the job state, and sufficient schedule/location information to distinguish similar jobs. The active job has a green border. Planned jobs have a gray or muted yellow border; the final color choice is part of the visual design. The state must also be displayed in text to ensure it is understandable without relying on color. A ****“New Job”**** action remains available.

Pressing a tab opens that job's detail screen. Dragging a tab changes its priority position and the order persists after the app closes. Dragging does not change which job is active.

### 2. New Job setup

Use a multi-step flow with **Back**, **Next**, and visible progress. Going back preserves entered data. The user can leave and resume an unfinished draft; an unfinished draft does not appear as a planned job until setup is completed. Each step supports editing after creation from job details.

| Step | Information and actions |
| --- | --- |
| 1. Clients | Add one or more clients. Each entry has a name and optional phone number. Allow multiple names and numbers without forcing them into one text field. An optional short job title can distinguish jobs for the same client. |
| 2. Job site | Enter the job's street address. Provide a **Navigate** action that opens the address in an available map app. |
| 3. Schedule | Choose a start date and optional end date with a calendar picker. Enter an optional start time. Display dates as Month Day, Year. **Add to calendar** opens a prefilled event for review. |
| 4. Work details | Enter a description of the job. |
| 5. Inventory | Add itemized names, quantities, and notes for what the job requires, or skip this step. |
| 6. Outside workers | Add zero or more worker entries, each with a name, optional contact number, and the specific work assigned to that worker. |
| 7. Photos | Add zero or more reference photos from the device and review or remove them before finishing. |
| 8. Review | Show the entered information, allow edits, and finish setup. If there is no active job, ask **“Activate this job now?”** with **Activate** and **Keep planned** choices. |

The app should allow saving a useful job even if schedule, workers, and photos are not yet known. At minimum, finishing requires a client name; the optional job title can be used as the home-screen label. Blank optional phone numbers are allowed; when a number is entered, the app checks that it can be used for a call. The app should explain missing required information next to the relevant field.

### 3. Job details

Opening a job shows its clients and phone numbers, address, schedule, work description, inventory, assigned outside workers, and reference photos. The screen provides actions to edit each section, call a saved number, open the address in maps, add the job to a calendar, change active/planned state, mark the job completed, and share the job. A Home button appears at the top right. Returning to Home preserves the current priority order.

Editing a job's dates updates the saved job immediately. A calendar event that the user already saved is not silently changed by editing the job; the app offers **Add to calendar** again with the revised information. Completed jobs remain accessible in a separate history/list and can be reopened or restored to planned. A per-job Costs screen stores cost entries separately and has its own PDF export. Costs do not appear in job QR codes or job PDFs.

### 4. Share and import by QR code

The job detail screen has a **Share job** action that generates a scannable QR code. Another person can scan it with JobTracker, review what will be imported, and save a separate copy in their own app. The recipient can cancel before saving. An imported job starts as **planned** and does not displace the recipient's active job.

The shared information includes the job's clients, address, schedule, description, inventory, and outside-worker assignments. Photos and costs are excluded from QR codes. QR sharing works offline; anyone who scans the code can read the snapshot, which does not expire. A separate job PDF includes job details, inventory, and reference photos for sending through Android's share sheet. It excludes costs. Later edits do not update an imported copy or an already exported PDF.

## Data to retain per job

- Stable job ID; short display title; state; priority position; creation and update timestamps.
- Clients: one or more records with name and optional phone number.
- Job site: entered address.
- Schedule: optional start date/time, optional end date, and time zone used for calendar export.
- Work details: free-text description.
- Inventory: zero or more itemized names, quantities, and notes.
- Costs: per-job private entries stored separately from the shared job record.
- Outside workers: zero or more records with name, optional phone number, and assigned work.
- Photos: references to locally stored image files, with a stable association to the job.

Job data and photo references must remain available after the app restarts. Removing a photo from a job should not delete unrelated photos on the device. Contacts and photos should only be shared after an explicit user action.

## Acceptance criteria

1. With no active/planned jobs, Home displays **“no jobs planned”** in gray and **“New Job”** immediately below it.
2. **New Job** opens the steps in the order above. Backward navigation retains entries; finishing creates one planned or active job according to the activation choice.
3. Finishing the first job offers activation. Finishing another job while one is active leaves the new job planned. The app never shows two active jobs.
4. Active and planned jobs have distinct borders and readable text labels. Tapping a job opens its full details.
5. Dragging a job tab changes its position without changing its state; the new order remains after restarting the app.
6. A saved address opens in a map app for navigation. A job with a start date can be handed to a calendar app with its title, location, and dates prefilled.
7. Every setup field can be edited later, including the schedule and inventory. Saved photos can be viewed and removed.
8. A job can be marked completed, disappears from the active/planned home list, and remains available in history.
9. The sharing flow displays a QR code. Scanning it in JobTracker leads to an import preview; accepting creates a planned copy without changing the recipient's existing jobs.
10. Job PDFs can be sent through Android's share sheet. The separate costs PDF contains per-job costs; neither job sharing method includes them.

## QR sharing decisions

1. **Transfer approach:** QR uses a small offline text-only snapshot. Photos can be included in the separate job PDF.
2. **Access and lifetime:** QR needs no sign-in, does not expire, and cannot be revoked. Anyone who scans it can read the shared client contact details.
3. **Duplicate imports:** Scanning the same snapshot again warns the recipient and permits another separate copy.

## Initial scope boundaries

This spec covers a single user's local job organization, private cost tracking, inventory, and one-time sharing/import. It does not define team collaboration, automatic calendar synchronization, worker invitations or payments, route planning, or a reusable contractor directory. Those can be added after the core job flow works.
