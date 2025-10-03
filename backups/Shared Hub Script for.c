// Shared Hub Script for ReplyNote App (Definitive Final Version)

function doGet(e) { return handleRequest(e); }
function doPost(e) { return handleRequest(e); }

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
    // Using Logger.log for better visibility in basic execution logs
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

// ==================== CORE STATS LOGIC (CORRECTED) ====================

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

    if (paras.length === 0 && !config.statsTop && !config.statsBottom) {
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
    const newTrackingText = '⏰\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;

    // --- Top Stats Logic ---
    let topPara = (paras.length > 0 && isStatsPara(paras[0])) ? paras[0] : null;
    if (config.statsTop) {
      if (topPara) { topPara.setText(newTrackingText); } 
      else { body.insertParagraph(0, newTrackingText); }
    } else if (topPara) {
      safeRemovePara(topPara);
    }

    // --- Bottom Stats Logic ---
    const updatedParas = body.getParagraphs();
    const topIsNowStats = updatedParas.length > 0 && isStatsPara(updatedParas[0]);
    let bottomFound = false;
    
    // Loop from the end, but crucially, STOP before index 0 if the top block is a stats block.
    for (let i = updatedParas.length - 1; i >= (topIsNowStats ? 1 : 0); i--) {
      if (isStatsPara(updatedParas[i])) {
        if (config.statsBottom) {
          updatedParas[i].setText(newTrackingText);
        } else {
          safeRemovePara(updatedParas[i]);
        }
        bottomFound = true;
        break;
      }
    }
    
    if (!bottomFound && config.statsBottom) {
      body.appendParagraph(newTrackingText);
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
