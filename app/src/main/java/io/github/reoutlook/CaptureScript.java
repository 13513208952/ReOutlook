package io.github.reoutlook;

/**
 * Experimental Outlook adapters. Authentication data is never sent across the native bridge or
 * persisted by the app. Backfill requests reuse Outlook's in-page request context only in memory.
 */
public final class CaptureScript {
    private CaptureScript() {}

    /** Installed before Outlook scripts so response clones can be normalized without altering them. */
    public static final String OWA_SERVICE_RESPONSES = """
            (() => {
              if (globalThis.__reOutlookFetchInstalled) return;
              globalThis.__reOutlookFetchInstalled = true;
              const originalFetch = globalThis.fetch.bind(globalThis);
              const bridge = globalThis.reOutlookBridge;
              const pending = [];
              const queued = new Set();
              let getItemsTemplate = null;
              let getItemsRequest = null;
              let backfillRunning = false;
              let sessionBackfillCount = 0;
              let sessionFindPageCount = 0;
              let sessionItemBodyFallbackCount = 0;
              let candidateBatchId = 0;
              let conversationAttemptId = 0;
              let accountHint = '';
              let accountHintRank = 0;
              const findStreams = new Map();
              const findContinuations = new Map();
              const candidateRevisions = new Map();
              const MAX_BACKFILL_PER_SESSION = 50;
              const MAX_EXTRA_FIND_PAGES_PER_SESSION = 20;
              const MAX_ITEM_BODY_FALLBACKS_PER_SESSION = 100;
              const MAX_CONVERSATION_ITEMS = 100;
              const pageSyncId = Date.now().toString(36) + '-' +
                Math.random().toString(36).slice(2);
              const clean = value => (value || '').toString().replace(/\\s+/g, ' ').trim();
              const escapeHtml = value => (value || '').toString()
                .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
              const send = value => {
                if (accountHint && !value.accountHint) value.accountHint = accountHint;
                bridge?.postMessage(JSON.stringify(value));
              };
              const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
              const rememberAccountHint = request => {
                const value = request?.headers?.get('x-anchormailbox') ||
                  request?.headers?.get('x-owa-explicitlogonuser') || '';
                const normalized = clean(value).slice(0, 512);
                if (!normalized || normalized === accountHint) return;
                const rank = /^puid:/i.test(normalized) ? 3 :
                  (/^(smtp:)?[^@\s]+@[^@\s]+$/i.test(normalized) ? 2 : 1);
                if (accountHint && rank <= accountHintRank) return;
                const previousAccountHint = accountHint;
                accountHint = normalized;
                accountHintRank = rank;
                send({type: 'account', previousAccountHint});
              };

              const isExplicitSuccess = value =>
                value?.ResponseClass === 'Success' && value?.ResponseCode === 'NoError';

              const publishItem = (item, syncToken) => {
                if (!item) return false;
                // Draft editing is outside the offline archive. Outlook can omit draft bodies even
                // when every submitted item in the conversation is complete.
                if (item.IsDraft === true) return true;
                const body = item.UniqueBody;
                if (!body || typeof body.Value !== 'string') return false;
                const mailbox = item.From?.Mailbox || item.Sender?.Mailbox || {};
                const html = body.BodyType === 'HTML'
                  ? body.Value
                  : '<pre>' + escapeHtml(body.Value) + '</pre>';
                send({
                  type: 'mail',
                  syncToken,
                  identity: item.InternetMessageId || item.ItemId?.Id || '',
                  subject: clean(item.Subject) || '（无主题）',
                  sender: clean(mailbox.Name || mailbox.EmailAddress),
                  receivedAt: item.DateTimeReceived || item.DateTimeSent || '',
                  bodyHtml: html.slice(0, 1500000),
                  bodyText: clean(body.Value.replace(/<[^>]*>/g, ' ')).slice(0, 200000),
                  sourceUrl: location.href
                });
                return body.IsTruncated !== true && html.length <= 1500000;
              };

              const fetchMissingItemBody = async item => {
                const itemId = item?.ItemId?.Id || '';
                if (!itemId || !getItemsTemplate || !getItemsRequest ||
                    sessionItemBodyFallbackCount >= MAX_ITEM_BODY_FALLBACKS_PER_SESSION) return null;
                sessionItemBodyFallbackCount++;
                try {
                  const itemShape = JSON.parse(JSON.stringify(
                    getItemsTemplate?.Body?.ItemShape || {}));
                  delete itemShape.CalculateOnlyFirstBody;
                  const payload = {
                    __type: 'GetItemJsonRequest:#Exchange',
                    Header: JSON.parse(JSON.stringify(getItemsTemplate.Header || {})),
                    Body: {
                      __type: 'GetItemRequest:#Exchange',
                      ItemShape: itemShape,
                      ItemIds: [{__type: 'ItemId:#Exchange', Id: itemId}],
                      ShapeName: 'ItemPart'
                    }
                  };
                  const url = new URL(getItemsRequest.url);
                  url.searchParams.set('action', 'GetItem');
                  const headers = new Headers(getItemsRequest.headers);
                  headers.set('action', 'GetItem');
                  const request = new Request(url.toString(), {
                    method: 'POST',
                    headers,
                    body: JSON.stringify(payload),
                    credentials: getItemsRequest.credentials,
                    cache: getItemsRequest.cache,
                    redirect: getItemsRequest.redirect,
                    referrerPolicy: getItemsRequest.referrerPolicy
                  });
                  const response = await originalFetch(request);
                  if (!response.ok) return null;
                  const result = await response.json();
                  const serviceResponse = result?.Body?.ResponseMessages?.Items?.[0];
                  if (!isExplicitSuccess(serviceResponse)) return null;
                  return serviceResponse?.Items?.[0] || null;
                } catch (_) {
                  return null;
                }
              };

              const publishItemWithFallback = async (item, syncToken) => {
                if (publishItem(item, syncToken)) return true;
                const body = item?.UniqueBody;
                if (!item || item.IsDraft === true ||
                    (body && typeof body.Value === 'string')) return false;
                const loadedItem = await fetchMissingItemBody(item);
                return publishItem(loadedItem, syncToken);
              };

              const inspectConversationItems = async (payload, requestedIds) => {
                const responses = payload?.Body?.ResponseMessages?.Items || [];
                const requested = new Set(requestedIds.filter(Boolean));
                const completed = new Set();
                const moreNeeded = new Set();
                for (let index = 0; index < responses.length; index++) {
                  const response = responses[index];
                  if (!isExplicitSuccess(response)) continue;
                  const conversation = response?.Conversation;
                  if (!conversation) continue;
                  let id = conversation?.ConversationId?.Id || '';
                  if (!id && requestedIds.length === responses.length) id = requestedIds[index] || '';
                  if (!id && requestedIds.length === 1) id = requestedIds[0];
                  const syncToken = pageSyncId + ':' + String(++conversationAttemptId);
                  const nodes = Array.isArray(conversation.ConversationNodes)
                    ? conversation.ConversationNodes
                    : [];
                  let bodiesComplete = Array.isArray(conversation.ConversationNodes);
                  let returnedItemCount = 0;
                  let expectedMailCount = 0;
                  for (const node of nodes) {
                    if (!Array.isArray(node?.Items)) {
                      bodiesComplete = false;
                      continue;
                    }
                    for (const item of node.Items) {
                      returnedItemCount++;
                      if (item?.IsDraft !== true) expectedMailCount++;
                      if (!await publishItemWithFallback(item, syncToken)) bodiesComplete = false;
                    }
                  }
                  const totalNodes = Number(conversation.TotalConversationNodesCount);
                  if (Number.isFinite(totalNodes) && totalNodes > nodes.length) {
                    bodiesComplete = false;
                    if (id) moreNeeded.add(id);
                  }
                  if (Number.isFinite(totalNodes) && totalNodes > 0 && returnedItemCount === 0) {
                    bodiesComplete = false;
                  }
                  if (!requested.has(id) || completed.has(id) || !bodiesComplete) {
                    send({type: 'conversationDiscard', syncToken});
                    continue;
                  }
                  completed.add(id);
                  send({
                    type: 'conversationComplete',
                    syncToken,
                    expectedMailCount,
                    conversationId: id,
                    revision: candidateRevisions.get(id) || ''
                  });
                }
                return {completed, moreNeeded};
              };

              const publishCandidates = (payload, continuation) => {
                const conversations = payload?.Body?.Conversations || [];
                const candidates = conversations.map(value => {
                  const id = value?.ConversationId?.Id || '';
                  const itemVersions = (value?.ItemIds || []).slice(-3).map(item =>
                    (item?.Id || '') + ':' + (item?.ChangeKey || '')).join(',');
                  const revision = [
                    value?.LastModifiedTime || value?.LastDeliveryTime || '',
                    value?.GlobalMessageCount ?? value?.MessageCount ?? '',
                    itemVersions
                  ].join('|').slice(0, 8192);
                  if (id) candidateRevisions.set(id, revision);
                  return {id, revision};
                }).filter(value => value.id);
                if (!candidates.length) {
                  continuation?.();
                  return 0;
                }
                const token = String(++candidateBatchId);
                if (continuation) findContinuations.set(token, continuation);
                send({type: 'candidates', token, candidates: candidates.slice(0, 200)});
                return candidates.length;
              };

              const parseUrlPostData = request => {
                const raw = request?.headers?.get('x-owa-urlpostdata');
                if (!raw) return null;
                try { return JSON.parse(raw); } catch (_) {
                  try { return JSON.parse(decodeURIComponent(raw)); } catch (_) { return null; }
                }
              };

              const findStreamKey = payload => {
                const body = payload?.Body || {};
                return [
                  body.ParentFolderId?.BaseFolderId?.Id || '',
                  body.FocusedViewFilter ?? '',
                  body.ViewFilter || ''
                ].join('|');
              };

              const handleFindResponse = (request, requestPayload, responsePayload) => {
                if (!isExplicitSuccess(responsePayload?.Body)) return;
                if (!requestPayload) {
                  publishCandidates(responsePayload, null);
                  return;
                }
                const conversations = responsePayload?.Body?.Conversations || [];
                const count = conversations.length;
                const key = findStreamKey(requestPayload);
                const offset = requestPayload?.Body?.Paging?.Offset || 0;
                const body = responsePayload?.Body || {};
                const nextOffset = Math.max(Number(body.IndexedOffset || 0), offset + count);
                const total = Number(body.TotalConversationsInView || 0);
                const existing = findStreams.get(key);
                const stream = existing || {request, payload: requestPayload, offsets: new Set()};
                stream.request = request;
                stream.payload = requestPayload;
                stream.offsets.add(offset);
                findStreams.set(key, stream);

                const continueScanning = () => {
                  if (nextOffset >= total || stream.offsets.has(nextOffset) ||
                      sessionFindPageCount >= MAX_EXTRA_FIND_PAGES_PER_SESSION ||
                      pending.length + sessionBackfillCount >= MAX_BACKFILL_PER_SESSION) return;
                  stream.offsets.add(nextOffset);
                  sessionFindPageCount++;
                  setTimeout(async () => {
                    try {
                      const nextPayload = JSON.parse(JSON.stringify(stream.payload));
                      nextPayload.Body.Paging.Offset = nextOffset;
                      const headers = new Headers(stream.request.headers);
                      headers.set('x-owa-urlpostdata', JSON.stringify(nextPayload));
                      const nextRequest = new Request(stream.request, {headers});
                      const response = await originalFetch(nextRequest);
                      if (!response.ok) return;
                      handleFindResponse(nextRequest, nextPayload, await response.json());
                    } catch (_) {}
                  }, 1200);
                };
                publishCandidates(responsePayload, continueScanning);
              };

              const rememberGetItemsRequest = (request, payload) => {
                if (!payload?.Body?.Conversations?.length) return;
                getItemsTemplate = payload;
                getItemsRequest = request;
                runBackfill();
              };

              const runBackfill = async () => {
                if (backfillRunning || !getItemsTemplate || !getItemsRequest) return;
                backfillRunning = true;
                while (pending.length && sessionBackfillCount < MAX_BACKFILL_PER_SESSION) {
                  const conversationId = pending.shift();
                  sessionBackfillCount++;
                  try {
                    const configuredMaximum = Number(
                      getItemsTemplate?.Body?.MaxItemsToReturn || 20);
                    let maxItems = Math.max(1, Math.min(
                      MAX_CONVERSATION_ITEMS, configuredMaximum));
                    while (true) {
                      const payload = JSON.parse(JSON.stringify(getItemsTemplate));
                      const request = payload.Body.Conversations[0];
                      request.ConversationId.Id = conversationId;
                      request.SyncState = '';
                      payload.Body.Conversations = [request];
                      payload.Body.MaxItemsToReturn = maxItems;
                      if (payload.Body.ItemShape) {
                        payload.Body.ItemShape.CalculateOnlyFirstBody = false;
                      }
                      const backfillRequest = new Request(getItemsRequest, {
                        body: JSON.stringify(payload)
                      });
                      const response = await originalFetch(backfillRequest);
                      if (!response.ok) throw new Error('HTTP ' + response.status);
                      const inspection = await inspectConversationItems(
                        await response.json(), [conversationId]);
                      if (inspection.completed.has(conversationId)) break;
                      if (!inspection.moreNeeded.has(conversationId) ||
                          maxItems >= MAX_CONVERSATION_ITEMS) {
                        throw new Error('Conversation response was incomplete');
                      }
                      maxItems = Math.min(MAX_CONVERSATION_ITEMS, maxItems + 10);
                      await sleep(300);
                    }
                    await sleep(900);
                  } catch (_) {
                    queued.delete(conversationId);
                    await sleep(2500);
                  }
                }
                backfillRunning = false;
              };

              bridge?.addEventListener('message', event => {
                try {
                  const message = JSON.parse(event.data);
                  if (message.type !== 'backfillDecision') return;
                  for (const id of (message.missing || [])) {
                    if (!id || queued.has(id)) continue;
                    queued.add(id);
                    pending.push(id);
                  }
                  const continuation = findContinuations.get(message.token);
                  findContinuations.delete(message.token);
                  runBackfill();
                  if (pending.length + sessionBackfillCount < MAX_BACKFILL_PER_SESSION) {
                    continuation?.();
                  }
                } catch (_) {}
              });

              globalThis.fetch = async (...args) => {
                let requestCopy = null;
                try {
                  requestCopy = new Request(args[0], args[1]);
                  rememberAccountHint(requestCopy);
                } catch (_) {}
                const response = await originalFetch(...args);
                try {
                  const url = new URL(requestCopy?.url || '', location.href);
                  if (url.origin !== location.origin || !url.pathname.endsWith('/owa/service.svc')) {
                    return response;
                  }
                  const action = url.searchParams.get('action');
                  if (action === 'FindConversation' && requestCopy && response.ok) {
                    const findPayload = parseUrlPostData(requestCopy);
                    response.clone().json()
                      .then(value => handleFindResponse(requestCopy, findPayload, value))
                      .catch(() => {});
                  } else if (action === 'GetConversationItems' && requestCopy && response.ok) {
                    Promise.all([requestCopy.clone().json(), response.clone().json()])
                      .then(async ([requestPayload, responsePayload]) => {
                        rememberGetItemsRequest(requestCopy, requestPayload);
                        const ids = (requestPayload?.Body?.Conversations || [])
                          .map(value => value?.ConversationId?.Id).filter(Boolean);
                        await inspectConversationItems(responsePayload, ids);
                      }).catch(() => {});
                  }
                } catch (_) {}
                return response;
              };
            })();
            """;

    /** Scrolls only the nearest scrollable ancestor of Outlook's rendered mail list rows. */
    public static final String SCROLL_MAIL_LIST = """
            (() => {
              const row = document.querySelector('[data-testid="MailListItem"]');
              if (!row) return 'no-mail-row';
              let node = row.parentElement;
              while (node && node !== document.body) {
                const style = getComputedStyle(node);
                const overflow = style.overflowY;
                if (node.scrollHeight > node.clientHeight + 40 &&
                    (overflow === 'auto' || overflow === 'scroll')) {
                  const before = node.scrollTop;
                  node.scrollBy({
                    top: Math.max(180, Math.round(node.clientHeight * 0.68)),
                    behavior: 'smooth'
                  });
                  return node.scrollTop >= node.scrollHeight - node.clientHeight - 8
                    ? 'bottom'
                    : 'scrolled:' + before;
                }
                node = node.parentElement;
              }
              return 'no-scroll-container';
            })()
            """;

    public static final String CURRENT_MESSAGE = """
            (() => {
              const first = (selectors) => {
                for (const selector of selectors) {
                  const node = document.querySelector(selector);
                  if (node) return node;
                }
                return null;
              };
              const clean = (value) => (value || '').replace(/\\s+/g, ' ').trim();
              const readingPane = document.querySelector('[data-testid="ReadingPane"]');
              const selectableRegions = readingPane
                ? [...readingPane.querySelectorAll('.allowTextSelection')]
                : [];
              const mobileBody = selectableRegions
                .filter(node => !node.querySelector('[role="heading"]'))
                .sort((a, b) => b.outerHTML.length - a.outerHTML.length)[0];
              const body = mobileBody || first([
                '[data-app-section="MailReadCompose"] [role="document"]',
                '[data-testid="message-body"]',
                '[aria-label="Message body"]',
                '[role="document"]'
              ]);
              if (!body || clean(body.innerText).length < 2) return null;

              const subjectNode = readingPane?.querySelector('[role="heading"]') || first([
                '[data-testid="message-subject"]',
                '[data-app-section="MailReadCompose"] h1',
                '[data-app-section="MailReadCompose"] h2'
              ]);
              const mobilePersona = readingPane?.querySelector('[data-testid="Persona"]');
              const senderNode = mobilePersona || first([
                '[data-testid="message-sender"]',
                '[data-app-section="MailReadCompose"] [aria-label^="From:"]',
                '[data-app-section="MailReadCompose"] [aria-label^="发件人"]'
              ]);
              const dateNode = readingPane?.querySelector('time[datetime]') || first([
                '[data-testid="message-date"]',
                '[data-app-section="MailReadCompose"] time',
                '[role="document"] time'
              ]);
              const selected = document.querySelector('[aria-selected="true"]');
              const identity = selected
                ? ['data-itemid', 'data-convid', 'data-message-id']
                    .map(name => selected.getAttribute(name)).filter(Boolean).join(':')
                : '';
              const title = clean(subjectNode?.innerText) ||
                clean(document.title.replace(/\\s+-\\s+Outlook.*$/i, '')) || '（无主题）';
              const sender = clean(senderNode?.getAttribute('aria-label') || senderNode?.innerText)
                .replace(/\\s*的联系人卡片.*$/, '')
                .replace(/\\s*contact card.*$/i, '');
              return JSON.stringify({
                identity: identity || location.href,
                subject: title,
                sender,
                receivedAt: clean(dateNode?.getAttribute('datetime') || dateNode?.innerText),
                bodyHtml: body.outerHTML.slice(0, 1500000),
                bodyText: clean(body.innerText).slice(0, 200000),
                sourceUrl: location.href
              });
            })()
            """;
}
