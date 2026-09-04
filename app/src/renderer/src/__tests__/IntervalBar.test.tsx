import { render, screen } from '@testing-library/react'
import { IntervalBar } from '../components/IntervalBar'

describe('IntervalBar', () => {
  it('clamps an inverted interval (high < low) so the bar never gets a negative width', () => {
    render(<IntervalBar low={20} high={10} value={15} max={40} />)
    const bar = screen.getByLabelText('interval 20.0 to 20.0 hours')
    const span = bar.querySelector('span')!
    expect(span.style.width).toBe('0%')
  })
  it('clamps a value below low up to low', () => {
    render(<IntervalBar low={10} high={20} value={-5} max={40} />)
    const bar = screen.getByLabelText('interval 10.0 to 20.0 hours')
    const marker = bar.querySelector('i')!
    const span = bar.querySelector('span')!
    expect(marker.style.left).toBe(span.style.left)
  })
  it('clamps a value above the (possibly clamped) high down to high', () => {
    render(<IntervalBar low={10} high={20} value={100} max={40} />)
    const bar = screen.getByLabelText('interval 10.0 to 20.0 hours')
    const marker = bar.querySelector('i')!
    // high = 20 out of max = 40 -> 50%
    expect(marker.style.left).toBe('50%')
  })
})
