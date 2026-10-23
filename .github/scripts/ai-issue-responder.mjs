import { readFileSync, appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { buildContext, redact } from './ai-issue-context.mjs';
import { buildRequest, callModel, models } from './ai-issue-provider.mjs';

const repository = 'IvanSolis1989/Aerove';
const categories = new Set(['bug', 'question', 'help wanted', 'enhancement', 'documentation']);
const isCommand = text => /^\/ai-help(?:\s|$)/.test(String(text || '').trim());
const isOwnComment = item => item.user?.login === 'github-actions[bot]' && item.user?.type === 'Bot';
const optedOut = issue => issue.labels?.some(label => label.name === 'ai:skip');

export function eventPlan(name, event, runId = '0') {
  if (event.issue?.pull_request || event.issue?.state === 'closed' || event.issue?.locked || optedOut(event.issue || {})) return null;
  if (name === 'workflow_dispatch') {
    const mode = event.inputs?.mode || 'preview';
    const value = event.inputs?.issue_number || '';
    if (!['preview', 'send'].includes(mode) || (value && !/^[1-9]\d{0,8}$/.test(value))) throw new Error('Invalid manual input');
    if (mode === 'send' && !value) throw new Error('Posting requires an issue number');
    return { number: Number(value), preview: mode === 'preview', deep: String(event.inputs?.deep_analysis) === 'true',
      marker: `<!-- aerove-ai:manual-${String(runId).replace(/\D/g, '')} -->` };
  }
  if (event.issue?.user?.type === 'Bot') return null;
  if (name === 'issues' && (event.action === 'opened' ||
      (event.action === 'labeled' && categories.has(event.label?.name)))) {
    return { number: event.issue.number, preview: false, deep: false, marker: '<!-- aerove-ai:first -->' };
  }
  if (name === 'issue_comment' && event.action === 'created' && event.comment?.user?.type !== 'Bot' &&
      ['OWNER', 'MEMBER', 'COLLABORATOR'].includes(event.comment?.author_association) && isCommand(event.comment.body)) {
    return { number: event.issue.number, preview: false, deep: true, commentId: event.comment.id,
      marker: `<!-- aerove-ai:comment-${event.comment.id} -->` };
  }
  return null;
}

export async function listComments(api, number) {
  const comments = [];
  for (let page = 1; page <= 20; page++) {
    const batch = await api(`/issues/${number}/comments?per_page=100&page=${page}`);
    comments.push(...batch);
    if (batch.length < 100) return comments;
  }
  throw new Error('Comment history exceeds the bounded reader; use manual triage');
}

export async function respond({ plan, api, root, sha, apiKey, baseUrl, requestModel = callModel, now = Date.now() }) {
  if (!plan || (plan.number && !Number.isSafeInteger(plan.number))) return { skipped: true, reason: 'Unsupported event' };
  const issue = plan.number ? await api(`/issues/${plan.number}`) : {
    title: '[验收样例] AEROVE Lite 在 Android 12 上无法安装',
    body: '这是部署验收用的虚构问题，不是真实用户报告。设备是 Android 12，下载了 Lite，提示无法安装。请解释最低系统要求和可以核实的替代方案。',
  };
  if (issue.pull_request || issue.state === 'closed' || issue.locked || optedOut(issue)) return { skipped: true, reason: 'Issue unavailable or opted out' };
  const comments = plan.number ? await listComments(api, plan.number) : [];
  if (comments.some(item => isOwnComment(item) && item.body?.includes(plan.marker))) return { skipped: true, reason: 'Already replied' };
  let comment = '';
  if (plan.commentId) {
    const current = await api(`/issues/comments/${plan.commentId}`);
    if (!isCommand(current.body) || current.user?.type === 'Bot' ||
        current.issue_url !== `https://api.github.com/repos/${repository}/issues/${plan.number}`) return { skipped: true, reason: 'Command no longer applies' };
    const permission = await api(`/collaborators/${encodeURIComponent(current.user.login)}/permission`);
    if (!['admin', 'maintain', 'write'].includes(permission.permission)) return { skipped: true, reason: 'Maintainer permission required' };
    if (comments.some(item => isOwnComment(item) && /<!-- aerove-ai:comment-/.test(item.body) &&
        now - Date.parse(item.created_at) < 60000)) return { skipped: true, reason: 'Follow-up cooldown' };
    comment = current.body;
  }
  const model = plan.deep ? models.followup : models.first;
  const context = buildContext(root, redact(`${issue.title}\n${issue.body}\n${comment}`), sha);
  const request = buildRequest({ issue, comment, history: comments, context, model });
  const result = await requestModel({ apiKey, baseUrl, request });
  const reply = result.reply.replaceAll(apiKey || '\0', '[凭据已隐藏]');
  if (plan.preview || result.status === 'missing_key') return { status: result.status, model, preview: reply, posted: false };
  const latest = await api(`/issues/${plan.number}`);
  if (latest.state === 'closed' || latest.locked || optedOut(latest)) return { skipped: true, reason: 'Issue changed while analysing' };
  if ((await listComments(api, plan.number)).some(item => isOwnComment(item) && item.body?.includes(plan.marker))) {
    return { skipped: true, reason: 'Already replied during analysis' };
  }
  const posted = await api(`/issues/${plan.number}/comments`, { body:
    `> 🤖 **AEROVE AI ${plan.deep ? '深度追问' : '初步分析'}** · ${model}\n\n${reply}\n\n---\n` +
    `AI 生成，尚未经维护者验证。维护者可评论 \`/ai-help\` 追问；最终处理由维护者决定。\n${plan.marker}` });
  return { status: result.status, model, posted: true, url: posted.html_url };
}

export async function main(env = process.env, fetcher = fetch) {
  if (env.GITHUB_REPOSITORY !== repository || env.GITHUB_REF !== 'refs/heads/main') throw new Error('Only official repository main can respond');
  const event = JSON.parse(readFileSync(env.GITHUB_EVENT_PATH, 'utf8'));
  const plan = eventPlan(env.GITHUB_EVENT_NAME, event, env.GITHUB_RUN_ID);
  const api = async (route, body) => {
    for (let attempt = 0; attempt < 2; attempt++) {
      let response;
      try {
        response = await fetcher(`https://api.github.com/repos/${repository}${route}`, {
          method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(30000),
          headers: { Authorization: `Bearer ${env.GH_TOKEN}`, Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json' },
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
        if (response.ok) return await response.json();
      } catch { /* Ambiguous writes must be inspected, never automatically repeated. */ }
      if (body || attempt === 1 || (response && response.status < 500)) throw new Error(`GitHub request failed (${response?.status || 'transport'})`);
    }
  };
  const result = await respond({ plan, api, root: process.cwd(), sha: env.GITHUB_SHA,
    apiKey: env.MIMO_API_KEY, baseUrl: env.MIMO_BASE_URL,
    requestModel: options => callModel({ ...options, fetcher }),
  });
  console.log(JSON.stringify({ status: result.status, skipped: result.skipped, posted: result.posted, model: result.model, url: result.url, reason: result.reason }));
  if (env.GITHUB_STEP_SUMMARY) appendFileSync(env.GITHUB_STEP_SUMMARY,
    `### AEROVE Issue assistant\n\n${result.url ? `[查看回复](${result.url})` : result.preview || result.reason}\n\nStatus: ${result.status || 'skipped'}\n`);
  if (result.status && result.status !== 'ok') process.exitCode = 1;
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch(() => {
  console.error('Issue assistant failed; inspect configuration or delivery before retrying. No automatic resend.');
  process.exitCode = 1;
});
