// Small dependency-free charts. Shapes are SVG; axis labels are HTML so they never stretch or overlap.

const compact = (v) => (v >= 1e5 ? `${(v / 1e5).toFixed(1)}L` : v >= 1e3 ? `${(v / 1e3).toFixed(v >= 1e4 ? 0 : 1)}k` : `${Math.round(v)}`);

function Frame({ max, labels, prefix, children }) {
  const step = Math.max(1, Math.ceil(labels.length / 6));
  return (
    <div className="ops-chartbox">
      <div className="ops-yaxis">{[max, max / 2, 0].map((v, i) => <span key={i}>{prefix}{compact(v)}</span>)}</div>
      <div className="ops-plot">
        {children}
        <div className="ops-xaxis">
          {labels.map((l, i) => <span key={i} style={{ visibility: i % step === 0 ? 'visible' : 'hidden' }}>{l}</span>)}
        </div>
      </div>
    </div>
  );
}

export function BarChart({ data, x, y, color = '#38bdf8', format = (v) => v, prefix = '₹' }) {
  if (!data?.length) return <p className="ops-muted">No data yet.</p>;
  const max = Math.max(...data.map((d) => Number(d[y]) || 0), 1);
  const w = 100 / data.length;
  return (
    <Frame max={max} labels={data.map((d) => d[x])} prefix={prefix}>
      <svg className="ops-chart" viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="bar chart">
        {[0, 50].map((g) => <line key={g} x1="0" x2="100" y1={g} y2={g} stroke="#24314f" strokeWidth="0.4" vectorEffect="non-scaling-stroke" />)}
        {data.map((d, i) => {
          const h = ((Number(d[y]) || 0) / max) * 100;
          return (
            <rect key={i} x={i * w + w * 0.12} y={100 - h} width={w * 0.76} height={h} fill={color}>
              <title>{`${d[x]}: ${format(d[y])}`}</title>
            </rect>
          );
        })}
      </svg>
    </Frame>
  );
}

export function LineChart({ data, x, lines, prefix = '₹' }) {
  if (!data?.length) return <p className="ops-muted">No data yet.</p>;
  const max = Math.max(...data.flatMap((d) => lines.map((l) => Number(d[l.key]) || 0)), 1);
  const px = (i) => (data.length === 1 ? 50 : (i / (data.length - 1)) * 100);
  const py = (v) => 100 - ((Number(v) || 0) / max) * 100;
  return (
    <Frame max={max} labels={data.map((d) => d[x])} prefix={prefix}>
      <svg className="ops-chart" viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="line chart">
        {[0, 50].map((g) => <line key={g} x1="0" x2="100" y1={g} y2={g} stroke="#24314f" strokeWidth="0.4" vectorEffect="non-scaling-stroke" />)}
        {lines.map((l) => (
          <polyline key={l.key} fill="none" stroke={l.color} strokeWidth="2" strokeDasharray={l.dashed ? '5 4' : undefined}
            points={data.map((d, i) => `${px(i)},${py(d[l.key])}`).join(' ')} vectorEffect="non-scaling-stroke" />
        ))}
      </svg>
    </Frame>
  );
}

export const inr = (v) => (v == null ? '–' : `₹${Math.round(Number(v)).toLocaleString('en-IN')}`);
