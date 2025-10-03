// Safe Debug Version - Won't modify your document content

// ==================== SAFE DEBUG FUNCTION ====================
// This will only LOG what it finds, won't modify anything
function debugCheckDocument() {
  // CHANGE THIS to your actual document ID
  const TEST_DOC_ID = 'YOUR_DOC_ID_HERE';
  
  Logger.log('=== DOCUMENT ANALYSIS ===');
  
  const doc = DocumentApp.openById(TEST_DOC_ID);
  const body = doc.getBody();
  const paras = body.getParagraphs();
  
  Logger.log('Total paragraphs: ' + paras.length);
  Logger.log('');
  
  // Check each paragraph
  for (let i = 0; i < paras.length; i++) {
    const para = paras[i];
    const text = para.getText();
    
    // Only log interesting paragraphs
    if (text.includes('⏰') || text.includes('⏳') || text.trim() === '⏳' || text.startsWith('⏳')) {
      Logger.log('Paragraph ' + i + ':');
      Logger.log('  Text: "' + text.substring(0, 100) + (text.length > 100 ? '...' : '') + '"');
      Logger.log('  Length: ' + text.length);
      Logger.log('  Starts with ⏰: ' + text.startsWith('⏰'));
      Logger.log('  Starts with ⏳: ' + text.startsWith('⏳'));
      Logger.log('  Equals just "⏳": ' + (text === '⏳'));
      Logger.log('  Equals trimmed "⏳": ' + (text.trim() === '⏳'));
      Logger.log('---');
    }
  }
}

// Test what happens when we call from the app
function debugSimulateAppCall() {
  // CHANGE THESE to match your setup
  const TEST_DOC_ID = 'YOUR_DOC_ID_HERE';
  const TEST_TOKEN = 'YOUR_TOKEN_HERE'; // Get this from your app's SharedPreferences
  
  Logger.log('=== SIMULATING APP CALL ===');
  
  // Simulate enabling statsAnywhere
  const params = {
    token: TEST_TOKEN,
    docId: TEST_DOC_ID,
    action: 'setConfig',
    statsTop: 'false',
    statsBottom: 'true',
    statsAnywhere: 'true',  // This is what gets sent when you check the box
    timezone: 'America/New_York'
  };
  
  // Create mock request object
  const mockRequest = {
    parameter: params,
    postData: null
  };
  
  Logger.log('Request parameters: ' + JSON.stringify(params));
  
  // Call the handler
  const result = handleRequest(mockRequest);
  Logger.log('Response: ' + result.getContent());
  
  // Now check what's in the document
  debugCheckDocument();
}

// ==================== MAIN HANDLERS ====================

function doGet(e) { return handleRequest(e); }
function doPost(e) { return handleRequest(e); }

function handleRequest(e) {
  try {
    const params = e.parameter;
    const token = params.token;
    const docId = params.docId;
    
    Logger.log('handleRequest - Received params: ' + JSON.stringify(params));
    
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
      case 'updateStats':
        return handleUpdateStats(token, docId);
      default:
        return createResponse({error: 'Unknown action: ' + action}, 400);
    }
  } catch (error) {
    Logger.log('Error in handleRequest: ' + error.toString() + ' Stack: ' + error.stack);
    return createResponse({error: 'Critical Error: ' + error.toString()}, 500);
  }
}

function handleUpdateStats(token, docId) {
  const doc = DocumentApp.openById(docId);
  const config = getDocConfig(token, docId);
  Logger.log('handleUpdateStats - Config: ' + JSON.stringify(config));
  updateStats(doc, config);
  return createResponse({success: true, message: 'Stats updated'});
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

  Logger.log('handleSetConfig - Before update: ' + JSON.stringify(config));
  Logger.log('handleSetConfig - Settings source: ' + JSON.stringify(settingsSource));

  if (settingsSource.statsTop !== undefined) {
    config.statsTop = settingsSource.statsTop === true || settingsSource.statsTop === 'true';
  }
  if (settingsSource.statsBottom !== undefined) {
    config.statsBottom = settingsSource.statsBottom === true || settingsSource.statsBottom === 'true';
  }
  if (settingsSource.statsAnywhere !== undefined) {
    config.statsAnywhere = settingsSource.statsAnywhere === true || settingsSource.statsAnywhere === 'true';
    Logger.log('handleSetConfig - statsAnywhere set to: ' + config.statsAnywhere);
  }
  if (settingsSource.timezone) config.timezone = settingsSource.timezone;
  
  Logger.log('handleSetConfig - After update: ' + JSON.stringify(config));
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

// ==================== CORE STATS LOGIC ====================

function updateStats(doc, config) {
  try {
    Logger.log('updateStats - Starting with config: ' + JSON.stringify(config));
    
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
      Logger.log('updateStats - No paragraphs and no stats enabled, returning');
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
    // Updated regex to clean both clock and sand timer stats
    let cleanText = fullText.replace(/[⏰⏳][\s\S]*?Status: .*?(?:\n|$)/g, '');
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
    const clockStatsText = '⏰\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;
    const sandTimerStatsText = '⏳\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;

    Logger.log('updateStats - Generated stats text');

    // --- Top Stats Logic (uses clock emoji) ---
    let topPara = (paras.length > 0 && isStatsPara(paras[0])) ? paras[0] : null;
    if (config.statsTop) {
      if (topPara) { 
        topPara.setText(clockStatsText); 
        Logger.log('updateStats - Updated existing top stats');
      } else { 
        body.insertParagraph(0, clockStatsText); 
        Logger.log('updateStats - Inserted new top stats');
      }
    } else if (topPara) {
      safeRemovePara(topPara);
      Logger.log('updateStats - Removed top stats');
    }

    // --- Bottom Stats Logic (uses clock emoji) ---
    const updatedParas = body.getParagraphs();
    const topIsNowStats = updatedParas.length > 0 && isStatsPara(updatedParas[0]);
    let bottomFound = false;
    
    for (let i = updatedParas.length - 1; i >= (topIsNowStats ? 1 : 0); i--) {
      if (isStatsPara(updatedParas[i])) {
        if (config.statsBottom) {
          updatedParas[i].setText(clockStatsText);
          Logger.log('updateStats - Updated bottom stats at para ' + i);
        } else {
          safeRemovePara(updatedParas[i]);
          Logger.log('updateStats - Removed bottom stats at para ' + i);
        }
        bottomFound = true;
        break;
      }
    }
    
    if (!bottomFound && config.statsBottom) {
      body.appendParagraph(clockStatsText);
      Logger.log('updateStats - Added new bottom stats');
    }
    
    // --- Sand Timer Stats Logic ---
    Logger.log('updateStats - statsAnywhere = ' + config.statsAnywhere + ' (type: ' + typeof config.statsAnywhere + ')');
    
    const finalParas = body.getParagraphs();
    let sandTimerCount = 0;
    let sandTimerStatsCount = 0;
    
    if (config.statsAnywhere === true) {
      Logger.log('updateStats - Looking for ⏳ markers to replace...');
      
      for (let i = 0; i < finalParas.length; i++) {
        const para = finalParas[i];
        const text = para.getText();
        
        // Check if this paragraph is JUST the sand timer emoji
        if (text === '⏳' || text.trim() === '⏳') {
          Logger.log('  Found ⏳ marker at paragraph ' + i);
          para.setText(sandTimerStatsText);
          sandTimerCount++;
        }
      }
      Logger.log('updateStats - Replaced ' + sandTimerCount + ' sand timer markers');
      
    } else if (config.statsAnywhere === false) {
      Logger.log('updateStats - Looking for ⏳ stats blocks to revert...');
      
      for (let i = 0; i < finalParas.length; i++) {
        const para = finalParas[i];
        const text = para.getText();
        
        // Check if this paragraph starts with sand timer stats
        if (text.startsWith('⏳\n')) {
          Logger.log('  Found ⏳ stats block at paragraph ' + i);
          para.setText('⏳');
          sandTimerStatsCount++;
        }
      }
      Logger.log('updateStats - Reverted ' + sandTimerStatsCount + ' sand timer stats blocks');
    } else {
      Logger.log('updateStats - statsAnywhere is not true or false, skipping sand timer logic');
    }
    
    // --- Save Properties ---
    props.setProperty(lastChangeTimeKey, lastChangeTime.toString());
    props.setProperty(lastLiveTimeKey, lastLiveTime.toString());
    props.setProperty(longestTimeKey, newLongestTime.toString());
    props.setProperty(lastContentKey, cleanText);
    
    Logger.log('updateStats - Complete');
    
  } catch (e) {
    Logger.log('CRITICAL Error in updateStats: ' + e.toString() + ' Stack: ' + e.stack);
  }
}

// ==================== HELPER FUNCTIONS ====================

function getDocConfig(token, docId) {
  const configKey = 'config_' + token + '_' + docId;
  const configStr = PropertiesService.getScriptProperties().getProperty(configKey);
  if (configStr) {
    const config = JSON.parse(configStr);
    Logger.log('getDocConfig - Retrieved: ' + JSON.stringify(config));
    return config;
  }
  const defaultConfig = { statsTop: false, statsBottom: true, statsAnywhere: false, timezone: 'UTC' };
  Logger.log('getDocConfig - Using default: ' + JSON.stringify(defaultConfig));
  return defaultConfig;
}

function isStatsPara(para) {
  try {
    // Only check for clock emoji, not sand timer
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