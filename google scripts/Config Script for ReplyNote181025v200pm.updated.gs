// Config Script for ReplyNote App - Enhanced Configuration & Guardrail Support
// Deploy this script with:
// Execute as: Me (mcarthur.sp@gmail.com)
// Who has access: Anyone

const CONFIG_DEFAULTS = {
  HUB_URL: 'https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec',
  MASTER_PASSWORD: 'sam03',
  NEXT_CONFIG_URL: 'https://script.google.com/macros/s/AKfycbybtpY6MlIg6pN8pMt8bNo9zwfRk4FciM6vAAdreb-4V20RweNQ6giIzPFXjrK5fhgOVQ/exec',
  QUOTA_DAILY_LIMIT: 20000,
  FREE_DAILY_QUOTA: 3,
  BULK_CAP_BYTES: 80 * 1024, // 80 KB
  FREE_QUOTA_SUSPENDED: false,
  GLOBAL_SYNC_SUSPENDED: false
};

const PROPERTY_KEYS = {
  HUB_URL: 'hubUrl',
  MASTER_PASSWORD: 'masterPassword',
  NEXT_CONFIG_URL: 'nextConfigUrl',
  QUOTA_DAILY_LIMIT: 'quotaDailyLimit',
  FREE_DAILY_QUOTA: 'freeDailyQuota',
  BULK_CAP_BYTES: 'bulkUploadCapBytes',
  FREE_QUOTA_SUSPENDED: 'freeQuotaSuspended',
  GLOBAL_SYNC_SUSPENDED: 'globalSyncSuspended',
  TOTAL_CLOUD_SENDS: 'totalCloudSends',
  FREE_SENDS_TODAY: 'freeSendsToday',
  PAID_SENDS_TODAY: 'paidSendsToday',
  LAST_COUNTER_RESET: 'dailyCountersDate',
  LAST_QUOTA_CHECK: 'lastQuotaCheck',
  CONSECUTIVE_ERRORS: 'consecutiveSyncErrors'
};

function doGet(e) {
  return handleRequest(e);
}

function doPost(e) {
  return handleRequest(e);
}

function handleRequest(e) {
  const params = e.parameter || {};
  const action = params.action || 'getConfig';

  try {
    switch (action) {
      case 'getConfig':
        return getConfig();
      case 'setConfig':
        return setConfig(e);
      case 'runGuardrail':
        runQuotaGuardrail(true);
        return createJsonResponse({ success: true });
      default:
        return createJsonResponse({ error: 'Unknown action' }, 400);
    }
  } catch (error) {
    console.error('Config script error', error);
    return createJsonResponse({ error: error.toString() }, 500);
  }
}

function getConfig() {
  const props = PropertiesService.getScriptProperties();
  ensureDefaults(props);
  resetDailyCountersIfNeeded(props);

  const response = {
    hubUrl: props.getProperty(PROPERTY_KEYS.HUB_URL) || CONFIG_DEFAULTS.HUB_URL,
    masterPassword: props.getProperty(PROPERTY_KEYS.MASTER_PASSWORD) || CONFIG_DEFAULTS.MASTER_PASSWORD,
    nextConfigUrl: props.getProperty(PROPERTY_KEYS.NEXT_CONFIG_URL) || CONFIG_DEFAULTS.NEXT_CONFIG_URL,
    quotaDailyLimit: parseInteger(props.getProperty(PROPERTY_KEYS.QUOTA_DAILY_LIMIT), CONFIG_DEFAULTS.QUOTA_DAILY_LIMIT),
    freeDailyQuota: parseInteger(props.getProperty(PROPERTY_KEYS.FREE_DAILY_QUOTA), CONFIG_DEFAULTS.FREE_DAILY_QUOTA),
    bulkUploadCapBytes: parseInteger(props.getProperty(PROPERTY_KEYS.BULK_CAP_BYTES), CONFIG_DEFAULTS.BULK_CAP_BYTES),
    freeQuotaSuspended: parseBoolean(props.getProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED), CONFIG_DEFAULTS.FREE_QUOTA_SUSPENDED),
    globalSyncSuspended: parseBoolean(props.getProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED), CONFIG_DEFAULTS.GLOBAL_SYNC_SUSPENDED),
    totalCloudSends: parseInteger(props.getProperty(PROPERTY_KEYS.TOTAL_CLOUD_SENDS), 0),
    freeSendsToday: parseInteger(props.getProperty(PROPERTY_KEYS.FREE_SENDS_TODAY), 0),
    paidSendsToday: parseInteger(props.getProperty(PROPERTY_KEYS.PAID_SENDS_TODAY), 0),
    lastQuotaCheck: props.getProperty(PROPERTY_KEYS.LAST_QUOTA_CHECK) || null,
    consecutiveSyncErrors: parseInteger(props.getProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS), 0)
  };

  return createJsonResponse(response);
}

function setConfig(e) {
  const props = PropertiesService.getScriptProperties();
  ensureDefaults(props);

  const params = e.parameter || {};
  const password = params.password;
  const currentPassword = props.getProperty(PROPERTY_KEYS.MASTER_PASSWORD) || CONFIG_DEFAULTS.MASTER_PASSWORD;
  if (password !== currentPassword) {
    return createJsonResponse({ error: 'Invalid password' }, 401);
  }

  let updates = {};
  if (e.postData && e.postData.contents) {
    try {
      updates = JSON.parse(e.postData.contents) || {};
    } catch (err) {
      console.warn('Failed to parse JSON body; falling back to query params');
    }
  }

  const merged = Object.assign({}, params, updates);

  writePropertyIfPresent(props, PROPERTY_KEYS.HUB_URL, merged.hubUrl, validateScriptUrl);
  writePropertyIfPresent(props, PROPERTY_KEYS.MASTER_PASSWORD, merged.masterPassword);
  writePropertyIfPresent(props, PROPERTY_KEYS.NEXT_CONFIG_URL, merged.nextConfigUrl, validateScriptUrl);
  writeIntegerPropertyIfPresent(props, PROPERTY_KEYS.QUOTA_DAILY_LIMIT, merged.quotaDailyLimit, 1000);
  writeIntegerPropertyIfPresent(props, PROPERTY_KEYS.FREE_DAILY_QUOTA, merged.freeDailyQuota, 0);
  writeIntegerPropertyIfPresent(props, PROPERTY_KEYS.BULK_CAP_BYTES, merged.bulkUploadCapBytes, 0);
  writeBooleanPropertyIfPresent(props, PROPERTY_KEYS.FREE_QUOTA_SUSPENDED, merged.freeQuotaSuspended);
  writeBooleanPropertyIfPresent(props, PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED, merged.globalSyncSuspended);

  if (merged.resetDailyCounters === 'true') {
    resetDailyCounters(props, true);
  }

  return getConfig();
}

function runQuotaGuardrail(manual) {
  const props = PropertiesService.getScriptProperties();
  ensureDefaults(props);
  resetDailyCountersIfNeeded(props);

  const quotaLimit = parseInteger(props.getProperty(PROPERTY_KEYS.QUOTA_DAILY_LIMIT), CONFIG_DEFAULTS.QUOTA_DAILY_LIMIT);
  const remaining = UrlFetchApp.getRemainingDailyQuota();
  const suspendThreshold = Math.min(2000, Math.floor(quotaLimit * 0.1));
  const resumeThreshold = Math.max(5000, Math.floor(quotaLimit * 0.25));

  let freeSuspended = parseBoolean(props.getProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED), CONFIG_DEFAULTS.FREE_QUOTA_SUSPENDED);
  let globalSuspended = parseBoolean(props.getProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED), CONFIG_DEFAULTS.GLOBAL_SYNC_SUSPENDED);

  if (remaining < suspendThreshold) {
    freeSuspended = true;
    props.setProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED, 'true');
  }

  const hubUrl = props.getProperty(PROPERTY_KEYS.HUB_URL) || CONFIG_DEFAULTS.HUB_URL;
  let healthOk = false;
  try {
    const response = UrlFetchApp.fetch(hubUrl + '?action=health', {
      method: 'get',
      muteHttpExceptions: true,
      followRedirects: true,
      validateHttpsCertificates: true
    });
    const status = response.getResponseCode();
    if (status === 200) {
      const body = JSON.parse(response.getContentText());
      healthOk = body && body.status === 'ok';
    }
    if (!healthOk) {
      recordSyncError(props);
    } else {
      resetSyncErrors(props);
    }
  } catch (err) {
    console.error('Health check failed', err);
    recordSyncError(props);
  }

  const consecutiveErrors = parseInteger(props.getProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS), 0);
  if (consecutiveErrors >= 3) {
    globalSuspended = true;
    props.setProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED, 'true');
  }

  if (remaining > resumeThreshold && healthOk && freeSuspended) {
    freeSuspended = false;
    props.setProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED, 'false');
  }

  if (globalSuspended && healthOk && consecutiveErrors === 0) {
    globalSuspended = false;
    props.setProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED, 'false');
  }

  props.setProperty(PROPERTY_KEYS.LAST_QUOTA_CHECK, new Date().toISOString());

  if (manual) {
    return { freeSuspended, globalSuspended, remaining, consecutiveErrors };
  }
}

function ensureDefaults(props) {
  if (!props.getProperty(PROPERTY_KEYS.HUB_URL)) {
    props.setProperty(PROPERTY_KEYS.HUB_URL, CONFIG_DEFAULTS.HUB_URL);
  }
  if (!props.getProperty(PROPERTY_KEYS.MASTER_PASSWORD)) {
    props.setProperty(PROPERTY_KEYS.MASTER_PASSWORD, CONFIG_DEFAULTS.MASTER_PASSWORD);
  }
  if (!props.getProperty(PROPERTY_KEYS.NEXT_CONFIG_URL)) {
    props.setProperty(PROPERTY_KEYS.NEXT_CONFIG_URL, CONFIG_DEFAULTS.NEXT_CONFIG_URL);
  }
  if (!props.getProperty(PROPERTY_KEYS.QUOTA_DAILY_LIMIT)) {
    props.setProperty(PROPERTY_KEYS.QUOTA_DAILY_LIMIT, String(CONFIG_DEFAULTS.QUOTA_DAILY_LIMIT));
  }
  if (!props.getProperty(PROPERTY_KEYS.FREE_DAILY_QUOTA)) {
    props.setProperty(PROPERTY_KEYS.FREE_DAILY_QUOTA, String(CONFIG_DEFAULTS.FREE_DAILY_QUOTA));
  }
  if (!props.getProperty(PROPERTY_KEYS.BULK_CAP_BYTES)) {
    props.setProperty(PROPERTY_KEYS.BULK_CAP_BYTES, String(CONFIG_DEFAULTS.BULK_CAP_BYTES));
  }
  if (!props.getProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED)) {
    props.setProperty(PROPERTY_KEYS.FREE_QUOTA_SUSPENDED, String(CONFIG_DEFAULTS.FREE_QUOTA_SUSPENDED));
  }
  if (!props.getProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED)) {
    props.setProperty(PROPERTY_KEYS.GLOBAL_SYNC_SUSPENDED, String(CONFIG_DEFAULTS.GLOBAL_SYNC_SUSPENDED));
  }
  if (!props.getProperty(PROPERTY_KEYS.TOTAL_CLOUD_SENDS)) {
    props.setProperty(PROPERTY_KEYS.TOTAL_CLOUD_SENDS, '0');
  }
  if (!props.getProperty(PROPERTY_KEYS.FREE_SENDS_TODAY)) {
    props.setProperty(PROPERTY_KEYS.FREE_SENDS_TODAY, '0');
  }
  if (!props.getProperty(PROPERTY_KEYS.PAID_SENDS_TODAY)) {
    props.setProperty(PROPERTY_KEYS.PAID_SENDS_TODAY, '0');
  }
  if (!props.getProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS)) {
    props.setProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS, '0');
  }
}

function resetDailyCountersIfNeeded(props) {
  const today = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
  const lastReset = props.getProperty(PROPERTY_KEYS.LAST_COUNTER_RESET);
  if (lastReset !== today) {
    resetDailyCounters(props, false);
  }
}

function resetDailyCounters(props, force) {
  props.setProperty(PROPERTY_KEYS.FREE_SENDS_TODAY, '0');
  props.setProperty(PROPERTY_KEYS.PAID_SENDS_TODAY, '0');
  props.setProperty(PROPERTY_KEYS.LAST_COUNTER_RESET, Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd'));
  if (force) {
    props.setProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS, '0');
  }
}

function recordSyncError(props) {
  const errors = parseInteger(props.getProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS), 0) + 1;
  props.setProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS, String(errors));
}

function resetSyncErrors(props) {
  props.setProperty(PROPERTY_KEYS.CONSECUTIVE_ERRORS, '0');
}

function writePropertyIfPresent(props, key, value, validator) {
  if (value === undefined || value === null || value === '') {
    return;
  }
  if (validator && !validator(value)) {
    throw new Error('Invalid value for ' + key);
  }
  props.setProperty(key, value);
}

function writeIntegerPropertyIfPresent(props, key, value, min) {
  if (value === undefined || value === null || value === '') {
    return;
  }
  const parsed = parseInt(value, 10);
  if (isNaN(parsed)) {
    throw new Error('Invalid numeric value for ' + key);
  }
  if (min !== undefined && parsed < min) {
    throw new Error('Value for ' + key + ' must be >= ' + min);
  }
  props.setProperty(key, String(parsed));
}

function writeBooleanPropertyIfPresent(props, key, value) {
  if (value === undefined || value === null || value === '') {
    return;
  }
  const bool = parseBoolean(value, false);
  props.setProperty(key, String(bool));
}

function parseBoolean(value, fallback) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  if (typeof value === 'boolean') {
    return value;
  }
  const normalized = String(value).toLowerCase();
  return normalized === 'true' || normalized === '1' || normalized === 'yes';
}

function parseInteger(value, fallback) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  const parsed = parseInt(value, 10);
  return isNaN(parsed) ? fallback : parsed;
}

function validateScriptUrl(url) {
  if (!url) return true;
  return url.indexOf('https://script.google.com/') === 0 && url.indexOf('/exec') > -1;
}

function createJsonResponse(data, status) {
  const output = ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
  if (status) {
    output.setResponseCode(status);
  }
  return output;
}

// Manual helpers for debugging
function testGetConfig() {
  Logger.log(getConfig().getContent());
}

function testSetConfig() {
  const response = setConfig({
    parameter: {
      action: 'setConfig',
      password: CONFIG_DEFAULTS.MASTER_PASSWORD,
      freeDailyQuota: '5'
    }
  });
  Logger.log(response.getContent());
}

function manualRunGuardrail() {
  const result = runQuotaGuardrail(true);
  Logger.log(JSON.stringify(result, null, 2));
}
