import https from "https";

function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    const options = {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Referer': 'https://www.599.com/live/',
        'Cookie': ''
      },
      timeout: 30000,
      followRedirects: true
    };
    
    const req = https.get(url, options, (res) => {
      console.log('Status:', res.statusCode);
      console.log('Headers:', JSON.stringify(res.headers, null, 2));
      
      let data = '';
      res.on('data', (chunk) => {
        data += chunk;
      });
      res.on('end', () => {
        resolve(data);
      });
    });
    
    req.on('error', (e) => {
      reject(e);
    });
    
    req.on('timeout', () => {
      req.destroy();
      reject(new Error('Request timeout'));
    });
  });
}

async function test() {
  const url = 'https://www.599.com/live/60_2944704/';
  try {
    const html = await fetchUrl(url);
    console.log('\nHTML length:', html.length);
    console.log('\nFirst 2000 chars:');
    console.log(html.substring(0, 2000));
    
  } catch (error) {
    console.error('Error:', error.message);
  }
}

test();