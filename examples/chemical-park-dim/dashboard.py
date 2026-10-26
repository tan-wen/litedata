"""Static, self-contained dashboard for the chemical-park DIM example."""
from __future__ import annotations

import html
import json
from typing import Any


def dimension_panel(report: dict) -> str:
    """Show observed members and SCD1 evidence, without substituting planned rows."""
    dimension = report.get("dimension", {})
    if not dimension:
        return '<section class="panel"><h2>DIM 公共企业维度</h2><p>尚无 DIM 实测结果。</p></section>'
    def escaped(value: Any) -> str:
        return html.escape(str(value), quote=True)
    members = "".join('<tr>' + ''.join(f'<td>{escaped(row[key])}</td>' for key in ("enterprise_id", "enterprise_name", "product_code")) + '</tr>'
                      for row in dimension.get("members", []))
    change = dimension.get("scd1", {})
    base = str(report.get("platform_url", "http://localhost:8080")).rstrip('/')
    links = f'<a href="{escaped(base + "/models/" + str(dimension["modelId"]))}" target="_blank" rel="noopener">查看 DIM 模型与字段角色</a>'
    if report.get("dimension_lineage_path"):
        links += f' · <a href="{escaped(base + report["dimension_lineage_path"])}" target="_blank" rel="noopener">追溯企业维度字段血缘</a>'
    change_html = '<p>尚无 SCD1 变更实测结果。</p>'
    if change.get("status") == "passed":
        steps = (("变更前", change["before"]), ("DIM 更新后", change["after"]), ("DWD 重算后", change["factAfter"]), ("恢复后", change["restored"]))
        cells = ''.join(f'<tr><td>{escaped(label)}</td><td>{escaped(value)}</td></tr>' for label, value in steps)
        change_html = f'<h3>SCD1 属性覆盖验证</h3><p>成员数 {escaped(change["memberCountBefore"])} → {escaped(change["memberCountAfter"])}，同一企业覆盖属性后保持一行。</p><div class="scroll"><table><thead><tr><th>阶段</th><th>企业名称</th></tr></thead><tbody>{cells}</tbody></table></div>'
    return f'''<section class="panel" id="public-dimension"><h2>DIM 公共企业维度</h2>
<p>表：{escaped(dimension['tableName'])} · 业务键：{escaped(dimension['businessKeys'])} · 展示字段：{escaped(dimension['labelField'])} · 更新策略：{escaped(dimension['strategy'])}</p>
<p>以下成员来自本次 Doris 查询；维度名称与产品编码由 DIM 统一维护，事实加工关联后写入 DWD。</p>
<div class="scroll"><table><thead><tr><th>企业业务键</th><th>企业名称</th><th>产品编码</th></tr></thead><tbody>{members}</tbody></table></div>
{change_html}<p>{links}</p><p class="label">SCD1 仅保留当前属性；历史事实宽表需要重新加工。本示例不包含 SCD2 历史拉链。</p></section>'''


def render_dashboard(report: dict, scenario: dict) -> str:
    """Return a UTF-8 HTML snapshot viewer for an observed runner report.

    The page deliberately receives only already-observed results; it performs no
    requests and contains no credentials or external assets.
    """
    payload = {"report": report, "scenario": scenario}
    data = json.dumps(payload, ensure_ascii=False, default=str).replace("<", "\\u003c")
    title = html.escape(str(report.get("prefix", scenario.get("prefix", "chemical-park-dim"))))
    dim_panel = dimension_panel(report)
    return f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>化工园区 DIM 能效看板 · {title}</title><style>
:root{{--navy:#12324a;--ink:#16384d;--muted:#6b8491;--teal:#087d7a;--aqua:#dff5f1;--line:#d9e6eb;--bg:#f4f8f9;--warn:#b45e28}}*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:14px/1.55 system-ui,-apple-system,"Microsoft YaHei",sans-serif}}header{{background:linear-gradient(120deg,#102e47,#087d7a);color:white;padding:30px max(20px,calc((100% - 1180px)/2)) 34px}}h1{{margin:0;font-size:28px}}h2{{font-size:18px;margin:0 0 14px}}h3{{font-size:14px;margin:0}}p{{margin:6px 0}}.sub{{opacity:.82}}main{{max-width:1180px;margin:auto;padding:20px}}.notice{{border-left:4px solid var(--teal);background:white;padding:12px 15px;margin-bottom:16px;border-radius:4px}}.grid{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}}.card,.panel{{background:white;border:1px solid var(--line);border-radius:10px;padding:16px;box-shadow:0 2px 8px #15354a0a}}.label{{color:var(--muted);font-size:12px}}.value{{font-size:25px;font-weight:700;margin-top:3px}}.unit{{font-size:12px;font-weight:400;color:var(--muted)}}.two{{display:grid;grid-template-columns:1.25fr .75fr;gap:16px;margin-top:16px}}.panel{{margin-top:16px}}select{{width:100%;padding:9px;border:1px solid var(--line);border-radius:6px;color:var(--ink);background:white}}.legend{{display:flex;gap:14px;flex-wrap:wrap;font-size:12px}}.dot{{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:4px}}svg{{width:100%;height:260px;background:#fbfdfd;border-radius:6px;margin-top:8px}}table{{border-collapse:collapse;width:100%;font-size:13px}}th,td{{padding:9px 8px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}}th{{color:var(--muted);font-weight:600}}.scroll{{overflow:auto}}.flag{{background:#fff0e6;color:#994312;font-weight:700}}.flow{{display:flex;align-items:center;gap:7px;flex-wrap:wrap}}.node{{padding:7px 9px;border-radius:5px;background:#e9f5f4;color:#075b58;font-size:12px}}.arrow{{color:#7a969f}}details{{margin-top:10px}}summary{{cursor:pointer;color:var(--teal)}}a{{color:#087d7a}}pre{{white-space:pre-wrap;overflow-wrap:anywhere}}.status{{padding:2px 7px;border-radius:99px;background:#e5f5ed;color:#176b46;font-size:12px}}.failed{{background:#fff0e8;color:#a54d1a}}@media(max-width:700px){{header{{padding:22px 16px}}main{{padding:14px}}.grid{{grid-template-columns:repeat(2,1fr)}}.two{{grid-template-columns:1fr}}.value{{font-size:21px}}}}
</style></head><body><header><h1>化工园区 DIM 能效数据应用</h1><div class="sub">公共企业维度 · SCD1 当前值 · 近七日能效 · 静态只读快照</div></header><main>
<div class="notice"><strong id="snapshot">已验证快照</strong>：展示本次受控 API 观测结果；样例计划仅用于解释演示范围，不替代实际平台数据。<span id="asof"></span></div>
<section class="grid" id="kpis"></section>
{dim_panel}
<section class="two"><div class="panel"><h2>企业日趋势</h2><label for="enterprise">选择企业</label><select id="enterprise" aria-label="选择企业"></select><div class="legend"><span><i class="dot" style="background:#087d7a"></i>蒸汽（吨）</span><span><i class="dot" style="background:#347fb7"></i>产量（吨）</span></div><svg id="trend" viewBox="0 0 700 250" preserveAspectRatio="none" role="img" aria-label="蒸汽和产量日趋势图"><title>蒸汽与产量日趋势</title></svg></div><div class="panel"><h2>口径说明</h2><p>业务问题：将企业每日蒸汽、电力、用水与产量汇成可追溯的园区能效视图。</p><p><b>吨产品蒸汽</b>为七日蒸汽总量 ÷ 七日产量总量，是加权比率，<b>不是</b>每日比率的平均值。</p><p>产量为 0 时，强度显示“—（产量为0）”，避免无意义除法。</p><p>橙色标记表示蒸汽强度 &gt; 1.8，仅为演示参数，非行业基准。</p></div></section>
<section class="panel"><h2>企业七日对比</h2><div class="scroll"><table><thead><tr><th>企业</th><th>有效天数</th><th>蒸汽（吨）</th><th>电力（kWh）</th><th>产量（吨）</th><th>吨产品蒸汽</th><th>吨产品电耗</th></tr></thead><tbody id="companies"></tbody></table></div></section>
<section class="panel"><h2>日明细与可追溯强度</h2><div class="scroll"><table><thead><tr><th>日期</th><th>企业</th><th>蒸汽（吨）</th><th>电力（kWh）</th><th>用水（m³）</th><th>产量（吨）</th><th>吨产品蒸汽</th><th>吨产品电耗</th></tr></thead><tbody id="daily"></tbody></table></div></section>
<section class="panel"><h2>数据闭环与平台血缘</h2><p>源端负值异常在增量重跑中由 <b>1 → 0</b> 修正，DWD 明细由 <b>23 → 24</b> 行恢复。当前看板仅呈现报告实际返回的验证结果。</p><div class="flow"><span class="node">3 个源表</span><span class="arrow">→</span><span class="node">ODS × 3</span><span class="arrow">→</span><span class="node">DIM 企业 + ODS 用能 / 产量</span><span class="arrow">→</span><span class="node">DWD 日明细</span><span class="arrow">→</span><span class="node">DWS 企业七日 / ADS 园区七日</span><span class="arrow">→</span><span class="node">指标</span><span class="arrow">→</span><span class="node">受控 API</span><span class="arrow">→</span><span class="node">看板</span></div><p id="links"></p><details><summary>查看阶段验证与原始结果（可折叠）</summary><div class="scroll"><table><thead><tr><th>阶段</th><th>状态</th><th>详情</th></tr></thead><tbody id="stages"></tbody></table></div><pre id="raw" aria-label="原始快照数据"></pre></details></section>
</main><script id="snapshot-data" type="application/json">{data}</script><script>
(()=>{{const D=JSON.parse(document.getElementById('snapshot-data').textContent),R=D.report||{{}},S=D.scenario||{{}},X=R.results||{{}}, q=s=>document.querySelector(s), n=v=>{{let x=Number(v);return Number.isFinite(x)?x.toLocaleString('zh-CN',{{maximumFractionDigits:3}}):'—'}}, esc=v=>String(v??'—');
const park=(X.park||[])[0]||{{}}, companies=X.enterprises||[], traceRows=X.trace||[], day=v=>String(v??'').slice(0,10), traceByDay=new Map(traceRows.map(r=>[String(r.enterprise_id)+'|'+day(r.stat_date),r])), ratio=(v,p)=>Number(p)===0?null:(Number.isFinite(Number(v))&&Number.isFinite(Number(p))?Number(v)/Number(p):null), trace=(X.daily||[]).map(r=>{{let t=traceByDay.get(String(r.enterprise_id)+'|'+day(r.stat_date)), own=(key)=>t&&Object.prototype.hasOwnProperty.call(t,key);return Object.assign({{}},r,t||{{}},{{stat_date:day(r.stat_date),steam_per_ton:own('steam_per_ton')?t.steam_per_ton:ratio(r.steam_tons,r.production_tons),kwh_per_ton:own('kwh_per_ton')?t.kwh_per_ton:ratio(r.electricity_kwh,r.production_tons)}})}}); q('#snapshot').textContent=R.status==='success'?'已验证快照':'验证未完成的快照';q('#asof').textContent=R.as_of?' 仓库日历：'+R.as_of:'';
let ks=[['七日蒸汽',park.steam_tons,'吨'],['七日电力',park.electricity_kwh,'kWh'],['七日产量',park.production_tons,'吨'],['加权吨产品蒸汽',park.steam_per_ton,'吨/吨']]; q('#kpis').replaceChildren(...ks.map(a=>{{let e=document.createElement('div');e.className='card';e.innerHTML='<div class="label">'+a[0]+'</div><div class="value">'+n(a[1])+' <span class="unit">'+a[2]+'</span></div>';return e}}));
const sel=q('#enterprise'), ids=[...new Map(trace.map(r=>[String(r.enterprise_id),r.enterprise_name])).entries()]; ids.forEach(([id,name])=>{{let o=document.createElement('option');o.value=id;o.textContent=name+'（'+id+'）';sel.append(o)}});
function intensity(v,p){{return Number(p)===0?'—（产量为0）':n(v)}} function render(){{let id=sel.value, rows=trace.filter(r=>String(r.enterprise_id)===id).sort((a,b)=>String(a.stat_date).localeCompare(String(b.stat_date)));q('#daily').replaceChildren(...rows.map(r=>{{let tr=document.createElement('tr'),sp=intensity(r.steam_per_ton, r.production_tons); if(Number(r.steam_per_ton)>1.8)tr.className='flag';[r.stat_date,r.enterprise_name,n(r.steam_tons),n(r.electricity_kwh),n(r.water_m3),n(r.production_tons),sp,intensity(r.kwh_per_ton,r.production_tons)].forEach(v=>{{let td=document.createElement('td');td.textContent=esc(v);tr.append(td)}});return tr}})); chart(rows)}}
function chart(rows){{let svg=q('#trend'), w=700,h=250,p=35,max=Math.max(1,...rows.flatMap(r=>[Number(r.steam_tons)||0,Number(r.production_tons)||0]));svg.setAttribute('viewBox','0 0 '+w+' '+h);let path=(key)=>rows.map((r,i)=>`${{i?'L':'M'}}${{p+i*(w-2*p)/Math.max(1,rows.length-1)}},${{h-p-(Number(r[key])||0)*(h-2*p)/max}}`).join(' ');svg.replaceChildren();[['#087d7a','steam_tons','蒸汽'],['#347fb7','production_tons','产量']].forEach(([c,k,label])=>{{let z=document.createElementNS('http://www.w3.org/2000/svg','path');z.setAttribute('d',path(k));z.setAttribute('fill','none');z.setAttribute('stroke',c);z.setAttribute('stroke-width','3');let t=document.createElementNS('http://www.w3.org/2000/svg','title');t.textContent=label;z.append(t);svg.append(z)}});rows.forEach((r,i)=>{{let t=document.createElementNS('http://www.w3.org/2000/svg','text');t.setAttribute('x',p+i*(w-2*p)/Math.max(1,rows.length-1));t.setAttribute('y',h-10);t.setAttribute('text-anchor','middle');t.setAttribute('font-size','10');t.setAttribute('fill','#55717d');t.textContent=String(r.stat_date).slice(5);svg.append(t)}})}}
q('#companies').replaceChildren(...companies.map(r=>{{let tr=document.createElement('tr');if(Number(r.steam_per_ton)>1.8)tr.className='flag';[r.enterprise_name,r.valid_days,n(r.steam_tons),n(r.electricity_kwh),n(r.production_tons),intensity(r.steam_per_ton,r.production_tons),intensity(r.kwh_per_ton,r.production_tons)].forEach(v=>{{let td=document.createElement('td');td.textContent=esc(v);tr.append(td)}});return tr}})); sel.onchange=render;render();
let stages=R.stages||[];q('#stages').replaceChildren(...stages.map(r=>{{let tr=document.createElement('tr');[r.stage,r.status,r.details].forEach((v,i)=>{{let td=document.createElement('td');td.textContent=typeof v==='object'?JSON.stringify(v):esc(v);if(i===1)td.className=String(v).toLowerCase()==='failed'?'status failed':'status';tr.append(td)}});return tr}}));let rs=R.resources||{{}}, composite=(S.metrics||[]).find(m=>m.type==='COMPOSITE'), metric=(composite&&rs.metrics||{{}})[composite&&composite.key]||Object.values(rs.metrics||{{}})[0], trans=Object.values(rs.transforms||{{}}).at(-1), base=(R.platform_url||'http://localhost:8080').replace(/\\/+$/,''), links=[[metric,'指标血缘','/metrics/'],[trans,'转换任务','/transforms/']];q('#links').replaceChildren('实际平台：',...links.filter(x=>x[0]).map(([id,label,path])=>{{let a=document.createElement('a');a.href=base+path+encodeURIComponent(id)+(label==='指标血缘'?'/lineage':'');a.textContent=label+'（'+id+'）';a.target='_blank';a.rel='noopener';return a}}));q('#raw').textContent=JSON.stringify({{status:R.status,results:X}},null,2);}})();
</script></body></html>'''
