// Shared Hub Script for ReplyNote App (Debug Version with Test Function)

function doGet(e) { return handleRequest(e); }
function doPost(e) { return handleRequest(e); }

// ==================== TEST FUNCTION ====================
// Run this function to test the Every ⏰⏰ feature
function testStatsAnywhere() {
  // CHANGE THIS to your test document ID
  const TEST_DOC_ID = 'YOUR_DOC_ID_HERE'; 
  const TEST_TOKEN = 'test_token';
  
  // Simulate the config
  const configKey = 'config_' + TEST_TOKEN + '_' + TEST_DOC_ID;
  
  // Test 1: Initial state (statsAnywhere undefined)
  Logger.log('=== TEST 1: Initial state (statsAnywhere undefined) ===');
  let config = { statsTop: false, statsBottom: true, timezone: 'UTC' };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  const doc1 = DocumentApp.openById(TEST_DOC_ID);
  updateStats(doc1, config);
  Logger.log('After initial update (undefined statsAnywhere)');
  
  // Test 2: Enable statsAnywhere
  Logger.log('\n=== TEST 2: Enable statsAnywhere ===');
  config = { statsTop: false, statsBottom: true, statsAnywhere: true, timezone: 'UTC' };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  const doc2 = DocumentApp.openById(TEST_DOC_ID);
  updateStats(doc2, config);
  Logger.log('After enabling statsAnywhere');
  
  // Test 3: Disable statsAnywhere
  Logger.log('\n=== TEST 3: Disable statsAnywhere ===');
  config = { statsTop: false, statsBottom: true, statsAnywhere: false, timezone: 'UTC' };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  const doc3 = DocumentApp.openById(TEST_DOC_ID);
  updateStats(doc3, config);
  Logger.log('After disabling statsAnywhere');
}

// ==================== MAIN HANDLERS ====================

function handleRequest(e) {
  try {
    const params = e.parameter;
    const token = params.token;
    const docId = params.docId;
    
    let action = params.action || 'append';
    let payload = null;
    if (e && e.postData && e.postData.contents) {
      try {
        payload = JSON.parse(e.postData.contents);
        if (payload.mode) {
          action = payload.mode;
        }
      } catch (err) {}
    }

    if (!token || !docId) {
      return createResponse({error: 'Invalid token or docId'}, 400);
    }
    
    switch(action) {
      case 'append':
        return handleAppend(token, docId, e, payload);
      case 'setConfig':
      case 'applyStatsSettings':
        return handleSetConfig(token, docId, params, payload);
      case 'registerDoc':
        return handleRegisterDoc(token, docId, params);
      default:
        return createResponse({error: 'Unknown action: ' + action}, 400);
    }
  } catch (error) {
    Logger.log('Error in handleRequest: ' + error.toString() + ' Stack: ' + error.stack);
    return createResponse({error: 'Critical Error: ' + error.toString()}, 500);
  }
}

function handleAppend(token, docId, e, payload) {
  const doc = DocumentApp.openById(docId);
  const body = doc.getBody();
  const config = getDocConfig(token, docId);
  
  const content = e.parameter.text || (e.postData ? e.postData.contents : '');
  if (!content) return createResponse({error: 'No content to append'}, 400);
  
  const paras = body.getParagraphs();
  let insertAt = paras.length;
  if (config.statsBottom && paras.length > 0 && isStatsPara(paras[paras.length - 1])) {
    insertAt = paras.length - 1;
  }

  body.insertParagraph(insertAt, "—\n\n" + content + "\n");
  
  updateStats(doc, config);
  return createResponse({ success: true, message: 'Content appended' });
}

function handleSetConfig(token, docId, params, payload) {
  const configKey = 'config_' + token + '_' + docId;
  const config = getDocConfig(token, docId);
  
  const settingsSource = payload || params;

  Logger.log('handleSetConfig - Incoming settings: ' + JSON.stringify(settingsSource));
  Logger.log('handleSetConfig - Current config: ' + JSON.stringify(config));

  if (settingsSource.statsTop !== undefined) {
    config.statsTop = settingsSource.statsTop === true || settingsSource.statsTop === 'true';
  }
  if (settingsSource.statsBottom !== undefined) {
    config.statsBottom = settingsSource.statsBottom === true || settingsSource.statsBottom === 'true';
  }
  if (settingsSource.statsAnywhere !== undefined) {
    config.statsAnywhere = settingsSource.statsAnywhere === true || settingsSource.statsAnywhere === 'true';
    Logger.log('handleSetConfig - Setting statsAnywhere to: ' + config.statsAnywhere);
  }
  if (settingsSource.timezone) config.timezone = settingsSource.timezone;
  
  Logger.log('handleSetConfig - Saving config: ' + JSON.stringify(config));
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  
  const doc = DocumentApp.openById(docId);
  updateStats(doc, config);
  return createResponse({success: true, config: config});
}

function handleRegisterDoc(token, docId, params) {
  const configKey = 'config_' + token + '_' + docId;
  const config = {
    statsTop: params.statsTop === 'true',
    statsBottom: params.statsBottom !== 'false',
    statsAnywhere: params.statsAnywhere === 'true',
    timezone: params.timezone || 'UTC',
  };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  
  const doc = DocumentApp.openById(docId);
  updateStats(doc, config);
  return createResponse({success: true, message: 'Document registered'});
}

// ==================== CORE STATS LOGIC (FIXED) ====================

function updateStats(doc, config) {
  try {
    const body = doc.getBody();
    
    // Clean up any empty paragraphs at the start of the document
    let paras = body.getParagraphs();
    while (paras.length > 0 && paras[0].getText().trim() === '') {
        safeRemovePara(paras[0]);
        paras = body.getParagraphs();
    }
    
    const props = PropertiesService.getScriptProperties();
    const docId = doc.getId();

    if (paras.length === 0 && !config.statsTop && !config.statsBottom && !config.statsAnywhere) {
      return;
    }

    // --- Calculation Phase ---
    const lastContentKey = 'lastContent_' + docId;
    const lastChangeTimeKey = 'lastChangeTime_' + docId;
    const longestTimeKey = 'longestTime_' + docId;
    const lastLiveTimeKey = 'lastLiveTime_' + docId;
    let lastStoredContent = props.getProperty(lastContentKey) || '';
    let lastChangeTime = parseInt(props.getProperty(lastChangeTimeKey), 10) || null;
    let longestTime = parseInt(props.getProperty(longestTimeKey) || '0', 10);
    let lastLiveTime = parseInt(props.getProperty(lastLiveTimeKey) || '0', 10);
    let fullText = body.getText();
    let cleanText = fullText.replace(/⏰[\s\S]*?Status: .*?(?:\n|$)/g, '');
    let bigChange = Math.abs(cleanText.length - lastStoredContent.length) > 5;
    const now = Date.now();
    if (bigChange || !lastChangeTime) {
      lastChangeTime = now;
      if (bigChange) lastLiveTime = now;
    } else if (!lastLiveTime) {
      lastLiveTime = now;
    }
    const elapsedTime = Math.max(now - lastChangeTime, 0);
    const newLongestTime = Math.max(longestTime, elapsedTime);
    const tz = config.timezone || 'UTC';
    const timestampStr = Utilities.formatDate(new Date(lastChangeTime), tz, 'dd/MM/yyyy hh:mm:ss a');
    const status = elapsedTime < 2 * 60 * 1000 ? "Live" : "Away";
    
    // Create two versions of stats text
    const singleEmojiStatsText = '⏰\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;
    const doubleEmojiStatsText = '⏰⏰\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;

    // --- Top Stats Logic (uses single emoji) ---
    let topPara = (paras.length > 0 && isStatsPara(paras[0])) ? paras[0] : null;
    if (config.statsTop) {
      if (topPara) { topPara.setText(singleEmojiStatsText); } 
      else { body.insertParagraph(0, singleEmojiStatsText); }
    } else if (topPara) {
      safeRemovePara(topPara);
    }

    // --- Bottom Stats Logic (uses single emoji) ---
    const updatedParas = body.getParagraphs();
    const topIsNowStats = updatedParas.length > 0 && isStatsPara(updatedParas[0]);
    let bottomFound = false;
    
    // Loop from the end, but crucially, STOP before index 0 if the top block is a stats block.
    for (let i = updatedParas.length - 1; i >= (topIsNowStats ? 1 : 0); i--) {
      if (isStatsPara(updatedParas[i])) {
        if (config.statsBottom) {
          updatedParas[i].setText(singleEmojiStatsText);
        } else {
          safeRemovePara(updatedParas[i]);
        }
        bottomFound = true;
        break;
      }
    }
    
    if (!bottomFound && config.statsBottom) {
      body.appendParagraph(singleEmojiStatsText);
    }
    
    // --- NEW: Every ⏰⏰ Stats Logic (uses double emoji) ---
    // CRITICAL FIX: Only process if statsAnywhere is explicitly defined
    Logger.log('updateStats - statsAnywhere value: ' + config.statsAnywhere);
    Logger.log('updateStats - typeof statsAnywhere: ' + typeof config.statsAnywhere);
    
    const finalParas = body.getParagraphs();
    
    if (config.statsAnywhere === true) {
      Logger.log('updateStats - statsAnywhere is TRUE, looking for ⏰⏰ markers');
      // Find all paragraphs containing just "⏰⏰" and replace with full stats
      for (let i = 0; i < finalParas.length; i++) {
        const para = finalParas[i];
        const text = para.getText();
        Logger.log('Checking paragraph ' + i + ': "' + text + '"');
        if (text === '⏰⏰' || text.trim() === '⏰⏰') {
          Logger.log('Found ⏰⏰ marker at paragraph ' + i + ', replacing with stats');
          para.setText(doubleEmojiStatsText);
        }
      }
    } else if (config.statsAnywhere === false) {
      Logger.log('updateStats - statsAnywhere is FALSE, reverting stats blocks to ⏰⏰');
      // Find all paragraphs starting with "⏰⏰\n" and revert them to just "⏰⏰"
      for (let i = 0; i < finalParas.length; i++) {
        const para = finalParas[i];
        const text = para.getText();
        if (text.startsWith('⏰⏰\n')) {
          Logger.log('Found stats block at paragraph ' + i + ', reverting to ⏰⏰');
          para.setText('⏰⏰');
        }
      }
    } else {
      Logger.log('updateStats - statsAnywhere is undefined/null, skipping Every ⏰⏰ logic');
    }
    
    // --- Save Properties ---
    props.setProperty(lastChangeTimeKey, lastChangeTime.toString());
    props.setProperty(lastLiveTimeKey, lastLiveTime.toString());
    props.setProperty(longestTimeKey, newLongestTime.toString());
    props.setProperty(lastContentKey, cleanText);
    
  } catch (e) {
    Logger.log('CRITICAL Error in updateStats: ' + e.toString() + ' Stack: ' + e.stack);
  }
}

// ==================== HELPER FUNCTIONS ====================

function getDocConfig(token, docId) {
  const configKey = 'config_' + token + '_' + docId;
  const configStr = PropertiesService.getScriptProperties().getProperty(configKey);
  Logger.log('getDocConfig - Retrieved config string: ' + configStr);
  if (configStr) {
    const parsed = JSON.parse(configStr);
    Logger.log('getDocConfig - Parsed config: ' + JSON.stringify(parsed));
    return parsed;
  }
  const defaultConfig = { statsTop: false, statsBottom: true, timezone: 'UTC' };
  Logger.log('getDocConfig - Using default config (no statsAnywhere): ' + JSON.stringify(defaultConfig));
  return defaultConfig;
}

function isStatsPara(para) {
  try {
    return para && para.getType() === DocumentApp.ElementType.PARAGRAPH && para.getText().startsWith('⏰');
  } catch (e) { return false; }
}

function safeRemovePara(para) {
  try {
    const parent = para.getParent();
    const index = parent.getChildIndex(para);
    if (index === parent.getNumChildren() - 1) {
      para.clear(); 
    } else {
      para.removeFromParent();
    }
  } catch (e) {
    try { para.setText(''); } catch (e2) {}
  }
}

function formatElapsedTime(ms) {
    if (ms < 1000) return "0 sec";
    const sec = 1000, min = 60 * sec, hour = 60 * min, day = 24 * hour;
    let parts = [];
    const days = Math.floor(ms / day); if (days > 0) parts.push(days + (days > 1 ? ' days' : ' day')); ms %= day;
    const hours = Math.floor(ms / hour); if (hours > 0) parts.push(hours + (hours > 1 ? ' hours' : ' hour')); ms %= hour;
    const minutes = Math.floor(ms / min); if (minutes > 0) parts.push(minutes + ' min'); ms %= min;
    const seconds = Math.floor(ms / sec); if (seconds > 0 || parts.length === 0) parts.push(seconds + ' sec');
    return parts.join(', ');
}

function createResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data)).setMimeType(ContentService.MimeType.JSON);
}