// Shared Hub Script for ReplyNote App (Definitive Final Version)

function doGet(e) { return handleRequest(e); }
function doPost(e) { return handleRequest(e); }

// ==================== MAIN HANDLERS ====================

function handleRequest(e) {
  try {
    const params = e.parameter;
    const token = params.token;
    const docId = params.docId;
    
    // Determine action from URL parameter first, then from POST body if available
    let action = params.action || 'append';
    let payload = null;
    if (e && e.postData && e.postData.contents) {
      try {
        payload = JSON.parse(e.postData.contents);
        if (payload.mode) {
          action = payload.mode; // Allow action to be defined in JSON body
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
      case 'applyStatsSettings': // Handle both action names for compatibility
        return handleSetConfig(token, docId, params, payload);
      case 'registerDoc':
        return handleRegisterDoc(token, docId, params);
      default:
        return createResponse({error: 'Unknown action: ' + action}, 400);
    }
  } catch (error) {
    console.error('Error in handleRequest:', error, error.stack);
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

  if (settingsSource.statsTop !== undefined) {
    config.statsTop = settingsSource.statsTop === true || settingsSource.statsTop === 'true';
  }
  if (settingsSource.statsBottom !== undefined) {
    config.statsBottom = settingsSource.statsBottom === true || settingsSource.statsBottom === 'true';
  }
  if (settingsSource.timezone) config.timezone = settingsSource.timezone;
  
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
    timezone: params.timezone || 'UTC',
  };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  
  const doc = DocumentApp.openById(docId);
  updateStats(doc, config);
  return createResponse({success: true, message: 'Document registered'});
}

// ==================== CORE STATS LOGIC (PERFECT REPLICATION) ====================

function updateStats(doc, config) {
  try {
    const body = doc.getBody();
    const props = PropertiesService.getScriptProperties();
    const docId = doc.getId();

    const lastContentKey = 'lastContent_' + docId;
    const lastChangeTimeKey = 'lastChangeTime_' + docId;
    const longestTimeKey = 'longestTime_' + docId;
    const lastLiveTimeKey = 'lastLiveTime_' + docId;

    let lastStoredContent = props.getProperty(lastContentKey) || '';
    let lastChangeTime = parseInt(props.getProperty(lastChangeTimeKey), 10) || null;
    let longestTime = parseInt(props.getProperty(longestTimeKey) || '0', 10);
    let lastLiveTime = parseInt(props.getProperty(lastLiveTimeKey) || '0', 10);

    let fullText = body.getText();
    
    // FIX: Check for paragraphs before checking config to prevent errors on empty docs
    const paras = body.getParagraphs();
    if (paras.length === 0 && !config.statsTop && !config.statsBottom) {
      return; // Nothing to do on a totally empty doc with stats disabled
    }

    let cleanText = fullText
      .replace(/^⏰[\s\S]*?Status: .*?(?:\n|$)/, '')
      .replace(/⏰[\s\S]*?Status: .*?(?:\n|$)S/, '');

    let bigChange = false;
    if (lastStoredContent || cleanText.trim().length > 0) {
      if (Math.abs(cleanText.length - lastStoredContent.length) > 5) {
        bigChange = true;
      }
    }
    
    const now = Date.now();
    if (bigChange) {
      lastChangeTime = now;
      lastLiveTime = now;
    } else if (!lastChangeTime) {
      lastChangeTime = now;
      lastLiveTime = now;
    }

    const elapsedTime = Math.max(now - lastChangeTime, 0);
    const newLongestTime = Math.max(longestTime, elapsedTime);

    const tz = config.timezone || 'Australia/Brisbane';
    const timestampStr = Utilities.formatDate(new Date(lastChangeTime), tz, 'dd/MM/yyyy hh:mm:ss a');
    const status = elapsedTime < 2 * 60 * 1000 ? "Live" : "Away";
    const newTrackingText =
      '⏰\n' +
      'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' +
      'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' +
      'Status: ' + status;
    
    // --- Document Modification (Replicating Original Script's Method) ---
    // FIX: Use a more reliable method to find the top paragraph instead of findText()
    let topPara = (paras.length > 0 && isStatsPara(paras[0])) ? paras[0] : null;

    if (config.statsTop) {
      if (topPara) {
        topPara.setText(newTrackingText);
      } else {
        body.insertParagraph(0, newTrackingText);
      }
    } else if (topPara) {
        safeRemovePara(topPara);
    }
    
    // Re-fetch paragraphs after potential top modification
    const updatedParas = body.getParagraphs();
    let bottomUpdatedOrRemoved = false;
    // Start loop from the end, but stop before the first element if it's a stats para (already handled)
    for (let i = updatedParas.length - 1; i >= (topPara ? 1 : 0); i--) {
      if (isStatsPara(updatedParas[i])) {
        if (config.statsBottom) {
          updatedParas[i].setText(newTrackingText);
        } else {
          safeRemovePara(updatedParas[i]);
        }
        bottomUpdatedOrRemoved = true;
        break; 
      }
    }
    
    if (!bottomUpdatedOrRemoved && config.statsBottom) {
      // Ensure we don't add a duplicate if the only paragraph is the top stats
      if (updatedParas.length === 0 || !isStatsPara(updatedParas[updatedParas.length - 1])) {
         body.appendParagraph(newTrackingText);
      }
    }
    
    props.setProperty(lastChangeTimeKey, lastChangeTime.toString());
    props.setProperty(lastLiveTimeKey, lastLiveTime.toString());
    props.setProperty(longestTimeKey, newLongestTime.toString());
    props.setProperty(lastContentKey, cleanText);
    
  } catch (e) {
    console.error('CRITICAL Error in updateStats:', e, e.stack);
  }
}

// ==================== HELPER FUNCTIONS ====================

function getDocConfig(token, docId) {
  const configKey = 'config_' + token + '_' + docId;
  const configStr = PropertiesService.getScriptProperties().getProperty(configKey);
  if (configStr) return JSON.parse(configStr);
  return { statsTop: false, statsBottom: true, timezone: 'UTC' };
}

function isStatsPara(para) {
  try {
    return para && para.getType() === DocumentApp.ElementType.PARAGRAPH && para.getText().startsWith('⏰');
  } catch (e) { return false; }
}

function safeRemovePara(para) {
  try {
    if (para.getParent().getNumChildren() < 2) {
      para.setText('');
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

function testToggleStatsOn() {
  // 1. CONFIGURE YOUR TEST HERE
  const testParameters = {
    // IMPORTANT: REPLACE THIS WITH YOUR ACTUAL GOOGLE DOC ID
    docId: '1s5LFnee5GdTKu0YbfKFfDVkg49GhGICs7z9lzdPkbcU',
    
    // This is a fake token for testing purposes. It just needs to be present.
    token: 'test-user-token-123',
    
    // We are testing the 'setConfig' action to turn stats ON
    action: 'setConfig',
    statsTop: 'true', // URL parameters are strings, so we use 'true'
    statsBottom: 'true'
  };

  // 2. SIMULATE THE EVENT OBJECT THE APP SENDS
  const e = {
    parameter: testParameters
  };

  // 3. RUN THE MAIN HANDLER AND LOG THE OUTPUT
  try {
    Logger.log("--- Starting testToggleStatsOn ---");
    Logger.log("Simulating call with parameters: " + JSON.stringify(e.parameter));
    
    // Call the main function just like the web app would
    const result = handleRequest(e);
    
    Logger.log("--- Test Finished ---");
    Logger.log("Function returned: " + result.getContent());
  } catch (err) {
    Logger.log("!!! AN ERROR OCCURRED !!!");
    Logger.log("Error Message: " + err.message);
    Logger.log("Error Stack: " + err.stack);
  }
}

function testToggleStatsOff() {
  // 1. CONFIGURE YOUR TEST HERE
  const testParameters = {
    // IMPORTANT: REPLACE THIS WITH YOUR ACTUAL GOOGLE DOC ID
    docId: '1s5LFnee5GdTKu0YbfKFfDVkg49GhGICs7z9lzdPkbcU',
    
    // This is a fake token for testing purposes.
    token: 'test-user-token-123',
    
    // We are testing the 'setConfig' action to turn stats OFF
    action: 'setConfig',
    statsTop: 'false',    // Set to 'false' to simulate unchecking
    statsBottom: 'false'  // Set to 'false' to simulate unchecking
  };

  // 2. SIMULATE THE EVENT OBJECT THE APP SENDS
  const e = {
    parameter: testParameters
  };

  // 3. RUN THE MAIN HANDLER AND LOG THE OUTPUT
  try {
    Logger.log("--- Starting testToggleStatsOff ---");
    Logger.log("Simulating call with parameters: " + JSON.stringify(e.parameter));
    
    // Call the main function just like the web app would
    const result = handleRequest(e);
    
    Logger.log("--- Test Finished ---");
    Logger.log("Function returned: " + result.getContent());
  } catch (err) {
    Logger.log("!!! AN ERROR OCCURRED !!!");
    Logger.log("Error Message: " + err.message);
    Logger.log("Error Stack: " + err.stack);
  }
}
