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


// ==================== CORE STATS LOGIC (WITH BOTTOM STATS DEBUGGING) ====================

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
    
    const paras = body.getParagraphs();
    if (paras.length === 0 && !config.statsTop && !config.statsBottom) {
      return;
    }

    // This line contains a typo, which we will fix now.
    let cleanText = fullText
      .replace(/^⏰[\s\S]*?Status: .*?(?:\n|$)/, '')
      .replace(/⏰[\s\S]*?Status: .*?(?:\n|$)/, ''); // Corrected: removed the trailing 'S'

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
    
    const updatedParas = body.getParagraphs();
    let bottomUpdatedOrRemoved = false;
    
    // --- START OF DETECTIVE LOGGING ---
    Logger.log("--- Starting Bottom Stats Scan ---");
    let bottomFound = false;
    for (let i = updatedParas.length - 1; i >= (topPara ? 1 : 0); i--) {
      const para = updatedParas[i];
      const text = para.getText();
      const isStats = isStatsPara(para);
      Logger.log("Bottom Scan Index [" + i + "]: Text='" + text + "', isStatsPara()=" + isStats);

      if (isStats) { // Use the variable we already calculated
        Logger.log("--> Found a stats paragraph. Proceeding with removal logic.");
        bottomFound = true;
        if (config.statsBottom) {
          updatedParas[i].setText(newTrackingText);
        } else {
          safeRemovePara(updatedParas[i]);
        }
        bottomUpdatedOrRemoved = true;
        break; 
      }
    }
    if (!bottomFound) {
      Logger.log("--> RESULT: No bottom stats paragraph was found during the scan.");
    }
    Logger.log("--- Finished Bottom Stats Scan ---");
    // --- END OF DETECTIVE LOGGING ---
    
    if (!bottomUpdatedOrRemoved && config.statsBottom) {
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
    const parent = para.getParent();
    const index = parent.getChildIndex(para);

    // Check if this is the VERY LAST child element in the body
    if (index === parent.getNumChildren() - 1) {
      // It's the last paragraph. The only reliable action is to clear it.
      para.clear(); 
    } else {
      // It's not the last paragraph, so it's safe to remove it completely.
      para.removeFromParent();
    }
  } catch (e) {
    // Fallback in case of any other errors
    try {
      para.setText('');
    } catch (e2) {}
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

/**
 * A dedicated test function to run our diagnostic.
 * Your Doc ID has been pre-filled.
 */
function testFindBottomStats() {
  const docId = '1s5LFnee5GdTKu0YbfKFfDVkg49GhGICs7z9lzdPkbcU';
  
  diagnoseBottomStats(docId);
}


/**
 * This function loops backwards from the end of the document and logs
 * exactly what it finds in each paragraph, telling us if it thinks
 * a paragraph is a stats block or not.
 */
function diagnoseBottomStats(docId) {
  try {
    Logger.log("--- Starting Bottom Stats Diagnostic ---");
    const doc = DocumentApp.openById(docId);
    const body = doc.getBody();
    const paras = body.getParagraphs();
    Logger.log("Total paragraphs found: " + paras.length);

    Logger.log("--- Scanning backwards from the end of the document ---");
    let found = false;
    for (let i = paras.length - 1; i >= 0; i--) {
      const para = paras[i];
      const text = para.getText();
      const isStats = isStatsPara(para);
      
      // Log the details of the current paragraph
      Logger.log("Index [" + i + "]: Text='" + text + "', isStatsPara()=" + isStats);

      if (isStats) {
        Logger.log("--> Found a stats paragraph at index " + i + ". This is the one that should be removed.");
        found = true;
        break; // Stop after finding the first one from the end
      }
    }
    
    if (!found) {
        Logger.log("--> RESULT: No paragraph was identified as a stats block while scanning from the end.");
    }
    
    Logger.log("--- Diagnostic Finished ---");
  } catch(e) {
    Logger.log("!!! ERROR in diagnostic: " + e.toString());
  }
}
