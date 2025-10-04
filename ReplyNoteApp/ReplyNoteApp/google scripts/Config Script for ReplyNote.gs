// Config Script for ReplyNote App
// Deploy this script with:
// Execute as: Me (mcarthur.sp@gmail.com)
// Who has access: Anyone

function doGet(e) {
  return handleRequest(e);
}

function doPost(e) {
  return handleRequest(e);
}

function handleRequest(e) {
  const params = e.parameter;
  const action = params.action || 'getConfig';
  
  try {
    switch(action) {
      case 'getConfig':
        return getConfig();
      case 'setConfig':
        return setConfig(e);
      default:
        return createJsonResponse({error: 'Unknown action'}, 400);
    }
  } catch (error) {
    console.error('Error:', error);
    return createJsonResponse({error: error.toString()}, 500);
  }
}

function getConfig() {
  const props = PropertiesService.getScriptProperties();
  
  // Get stored values or use defaults
  const hubUrl = props.getProperty('hubUrl') || 'https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec';
  const masterPassword = props.getProperty('masterPassword') || 'sam03';
  
  // Get nextConfigUrl - defaults to self (current URL)
  const currentUrl = 'https://script.google.com/macros/s/AKfycbybtpY6MlIg6pN8pMt8bNo9zwfRk4FciM6vAAdreb-4V20RweNQ6giIzPFXjrK5fhgOVQ/exec';
  const nextConfigUrl = props.getProperty('nextConfigUrl') || currentUrl;
  
  return createJsonResponse({
    hubUrl: hubUrl,
    masterPassword: masterPassword,
    nextConfigUrl: nextConfigUrl
  });
}

function setConfig(e) {
  const params = e.parameter;
  const password = params.password;
  
  // Get current master password
  const props = PropertiesService.getScriptProperties();
  const currentPassword = props.getProperty('masterPassword') || 'sam03';
  
  // Check password
  if (password !== currentPassword) {
    return createJsonResponse({error: 'Invalid password'}, 401);
  }
  
  // Parse the POST body if present
  let updates = {};
  if (e.postData && e.postData.contents) {
    try {
      updates = JSON.parse(e.postData.contents);
    } catch (err) {
      // Try URL parameters as fallback
      if (params.hubUrl) updates.hubUrl = params.hubUrl;
      if (params.masterPassword) updates.masterPassword = params.masterPassword;
    }
  } else {
    // Use URL parameters
    if (params.hubUrl) updates.hubUrl = params.hubUrl;
    if (params.masterPassword) updates.masterPassword = params.masterPassword;
  }
  
  // Update properties
  if (updates.hubUrl) {
    props.setProperty('hubUrl', updates.hubUrl);
  }
  if (updates.masterPassword) {
    props.setProperty('masterPassword', updates.masterPassword);
  }
  if (updates.nextConfigUrl) {
    props.setProperty('nextConfigUrl', updates.nextConfigUrl);
  }
  
  return createJsonResponse({
    success: true,
    message: 'Configuration updated',
    hubUrl: props.getProperty('hubUrl'),
    masterPassword: props.getProperty('masterPassword'),
    nextConfigUrl: props.getProperty('nextConfigUrl')
  });
}

function createJsonResponse(data, status = 200) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}

// Manual testing functions
function testGetConfig() {
  const response = getConfig();
  console.log(response.getContent());
}

function testSetConfig() {
  // Simulate a request
  const e = {
    parameter: {
      action: 'setConfig',
      password: 'sam03',
      hubUrl: 'https://script.google.com/macros/s/NEW_DEPLOYMENT_ID/exec',
      masterPassword: 'newPassword123'
    }
  };
  
  const response = setConfig(e);
  console.log(response.getContent());
}

function resetToDefaults() {
  const props = PropertiesService.getScriptProperties();
  props.deleteAllProperties();
  console.log('Reset to defaults complete');
}