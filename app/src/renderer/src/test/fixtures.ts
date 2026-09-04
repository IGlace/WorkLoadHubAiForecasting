import type { RunCreated, RunDetail } from '../../../shared/types'

const W1 = '2026-09-07', W2 = '2026-09-14'
const row = (member_id: number, week_start: string, demand: number, capacity: number, low = demand - 3, high = demand + 3) => ({
  run_id: 5, member_id, week_start, demand_hours: demand, demand_low: low, demand_high: high, capacity_hours: capacity,
  overload_hours: Math.max(0, demand - capacity), open_task_hours: demand * 0.6, new_task_hours: demand * 0.4,
})

export const RUN_CREATED: RunCreated = {
  run_id: 5, team_id: 1, as_of: '2026-09-04', weeks: [W1, W2], champion: 'gbm', backtest_mase: 0.77,
  forecasts: [row(11, W1, 36, 40), row(11, W2, 38, 40), row(13, W1, 46, 40), row(13, W2, 44, 32)],
}

export const RUN_DETAIL: RunDetail = {
  run: { id: 5, team_id: 1, as_of: '2026-09-04', requested_by: 11, status: 'done', champion_model: 'gbm', backtest_mase: 0.77, started_at: '2026-09-04T09:00:00', finished_at: '2026-09-04T09:00:05', ai_status: 'ok' },
  forecasts: RUN_CREATED.forecasts,
  facts: {
    run: { id: 5, as_of: '2026-09-04', weeks: [W1, W2], generated_at: '2026-09-04T09:00:05' },
    team: { id: 1, name: 'Core', department_id: 1, team_leader_id: 11, totals: [{ week: W1, demand: 82, capacity: 80 }, { week: W2, demand: 82, capacity: 72 }] },
    members: [
      { id: 11, name: 'Ali Benjelloun', role: 'team_leader', history_13w: Array.from({ length: 13 }, (_, i) => ({ week: `2026-0${i < 4 ? 6 : i < 8 ? 7 : 8}-0${(i % 4) + 1}`, hours: 30 + i, tasks: 3 })),
        forecast: [{ week: W1, demand: 36, low: 33, high: 39, capacity: 40, overload: 0, open_hours: 21.6, new_hours: 14.4 }, { week: W2, demand: 38, low: 35, high: 41, capacity: 40, overload: 0, open_hours: 22.8, new_hours: 15.2 }],
        patterns: { member_id: 11, trend_hours_per_week: 0.4, top_weekday: 'Monday', weekday_shares: { Monday: 0.4, Tuesday: 0.2, Wednesday: 0.2, Thursday: 0.1, Friday: 0.1 }, estimate_ratio_median: 1.1, cycle_days_median: 4, cycle_days_by_type: { bug: 2, feature: 6 }, lateness_days_median: 0, share_late: 0.1, deadline_proximity_corr: null, share_with_project: 0.7, hours_by_project: { '3': 0.7 }, open_tasks: 4, open_est_hours: 30, overdue_open: 1, cluster: 0 },
        open_tasks: [{ id: 900, title: 'Fix login', type: 'bug', priority: 'high', estimated_hours: 6, due_date: '2026-09-02', overdue: true, project_id: 3 }] },
      { id: 13, name: 'Yara Tazi', role: 'member', history_13w: [], forecast: [{ week: W1, demand: 46, low: 43, high: 49, capacity: 40, overload: 6, open_hours: 27.6, new_hours: 18.4 }, { week: W2, demand: 44, low: 41, high: 47, capacity: 32, overload: 12, open_hours: 26.4, new_hours: 17.6 }],
        patterns: { member_id: 13, trend_hours_per_week: 1.2, top_weekday: 'Friday', weekday_shares: {}, estimate_ratio_median: 0.9, cycle_days_median: 5, cycle_days_by_type: {}, lateness_days_median: 1, share_late: 0.3, deadline_proximity_corr: 0.4, share_with_project: 0.5, hours_by_project: {}, open_tasks: 6, open_est_hours: 44, overdue_open: 2, cluster: 1 }, open_tasks: [] },
    ],
    projects: [{ id: 3, name: 'Billing v2', start_date: '2026-08-03', deadline: '2026-09-18', status: 'active', type: 'delivery', active_in_window: true, starting_in_window: false, ending_in_window: true }],
    model: { champion: 'gbm', champion_mase: 0.77, mase_by_model: { gbm: 0.77, tsb: 0.85, seasonal_naive: 1.0 }, backtest_origins: [], horizons: [1, 2], limitations: '', interval: { basis: '', horizons: {} } },
    rebalancing_candidates: { overloaded: [{ member_id: 13, name: 'Yara Tazi', overload_hours: 18 }], underloaded: [{ member_id: 11, name: 'Ali Benjelloun', spare_hours: 6 }] },
  },
  narrative: {
    run_summary: 'Core is slightly over capacity in both weeks, driven by Yara.',
    members: [
      { member_id: 11, name: 'Ali Benjelloun', risk_level: 'low', summary: 'Steady load around 36.0 h.', patterns: [{ kind: 'weekday_rhythm', statement: 'Most tasks arrive on Monday.', evidence: 'Monday share 40%.' }], warnings: [] },
      { member_id: 13, name: 'Yara Tazi', risk_level: 'high', summary: 'Demand of 46.0 h against 40.0 h capacity.', patterns: [], warnings: ['Two overdue tasks.'] },
    ],
    team_risks: [{ title: 'Billing v2 deadline', detail: 'Ends in week 2 while Yara is overloaded.', severity: 'medium', member_ids: [13] }],
    rebalancing: [{ from_member_id: 13, to_member_id: 11, week: W1, hours: 4, reason: 'Ali has 4.0 h spare.', confidence: 'medium' }],
    suggested_adjustments: [],
    model_notes: 'Champion gbm beat TSB.',
  },
}
