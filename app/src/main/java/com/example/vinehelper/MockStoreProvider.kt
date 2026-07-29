package com.example.vinehelper

object MockStoreProvider {
    fun getMockHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Amazon Vine Portal - Mock Mode</title>
        <style>
          body {
            background-color: #141923;
            color: #e2e8f0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            margin: 0;
            padding: 16px;
          }
          #vvp-items-button-container {
            display: flex;
            gap: 10px;
            margin-bottom: 20px;
          }
          .a-button {
            background: #FF9900;
            border: none;
            color: black;
            padding: 10px 20px;
            border-radius: 4px;
            cursor: pointer;
            font-weight: bold;
            text-decoration: none;
            display: inline-block;
          }
          #vvp-items-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 16px;
          }
          .vvp-item-tile {
            background: #1f2635;
            border: 1px solid #2d3748;
            border-radius: 8px;
            padding: 12px;
            width: 220px;
            box-sizing: border-box;
            display: flex;
            flex-direction: column;
            align-items: center;
          }
          .vvp-item-tile img {
            max-width: 120px;
            height: 120px;
            object-fit: contain;
            margin-bottom: 10px;
          }
          .vvp-item-product-title-container a {
            color: #00C6FF;
            text-decoration: none;
            font-size: 13px;
            height: 36px;
            overflow: hidden;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            margin-bottom: 10px;
          }
          .vvp-details-btn {
            width: 100%;
          }
          .vvp-details-btn input {
            background: #FF9900;
            border: none;
            color: black;
            width: 100%;
            padding: 8px 0;
            border-radius: 4px;
            cursor: pointer;
            font-weight: bold;
          }
          
          /* Mock Popover styles */
          .a-popover-scrim {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background-color: rgba(0,0,0,0.7);
            z-index: 9999;
            display: none;
          }
          .a-popover {
            position: fixed;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            width: 90%;
            max-width: 500px;
            background: #1a202c;
            border: 1px solid #4a5568;
            border-radius: 8px;
            z-index: 10000;
            display: none;
            box-shadow: 0 4px 20px rgba(0,0,0,0.5);
          }
          .a-popover-header {
            background: #2d3748;
            padding: 12px 16px;
            border-bottom: 1px solid #4a5568;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-top-left-radius: 8px;
            border-top-right-radius: 8px;
          }
          .a-popover-header-content {
            font-weight: bold;
            color: #f7fafc;
          }
          .a-button-close {
            background: transparent;
            border: none;
            color: #a0aec0;
            font-size: 20px;
            cursor: pointer;
          }
          .a-popover-inner {
            padding: 16px;
          }
          #vvp-product-details-modal img {
            max-height: 150px;
            display: block;
            margin: 0 auto 15px auto;
          }
          .modal-buttons {
            display: flex;
            justify-content: flex-end;
            gap: 10px;
            margin-top: 15px;
          }
        </style>
        </head>
        <body>
        <h1>Amazon Vine - Mock Portal</h1>
        <div id="vvp-items-button-container">
          <a class="a-button" href="https://www.amazon.com/vine/vine-items?queue=potluck">RFY (Potluck)</a>
          <a class="a-button" href="https://www.amazon.com/vine/vine-items?queue=last_chance">AFA (Last Chance)</a>
          <a class="a-button" href="https://www.amazon.com/vine/vine-items?queue=encore">AI (Encore)</a>
        </div>

        <div id="vvp-items-grid">
          <!-- Mock Tiles populated by JS depending on the queue -->
        </div>

        <!-- Mock Popover -->
        <div class="a-popover-scrim" id="scrim"></div>
        <div class="a-popover" id="popover">
          <div class="a-popover-header">
            <span class="a-popover-header-content">Product Details</span>
            <button class="a-button-close" onclick="closeModal()">&times;</button>
          </div>
          <div class="a-popover-inner">
            <div id="vvp-product-details-modal">
              <img id="modal-img" src="" alt="product-img">
              <h3 id="modal-title"></h3>
              <p id="modal-asin"></p>
              <div class="modal-buttons">
                <button class="a-button" style="background:#4a5568; color:white;" onclick="closeModal()">Back</button>
                <span class="a-button a-button-primary" id="vvp-join-btn">
                  <span class="a-button-inner">
                    <input class="a-button-input" type="submit" value="Request Product" onclick="requestProduct()">
                  </span>
                </span>
              </div>
            </div>
          </div>
        </div>

        <script>
          var urlParams = new URLSearchParams(window.location.search);
          var queue = urlParams.get('queue') || 'potluck';
          
          // Create mock items based on the queue
          var items = [];
          if (queue === 'potluck') {
            items = [
              { asin: 'MOCKRFY001', title: 'Premium Wireless Mechanical Keyboard (RGB, Blue Switches)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZmZhNzI2Ii8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+S2V5Ym9hcmQ8L3RleHQ+PC9zdmc+', recId: 'rec_rfy_1' },
              { asin: 'MOCKRFY002', title: 'Ergonomic Vertical Mouse (Wireless, Rechargeable)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjMjZjNmRhIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+TW91c2U8L3RleHQ+PC9zdmc+', recId: 'rec_rfy_2' }
            ];
          } else if (queue === 'last_chance') {
            items = [
              { asin: 'MOCKAFA001', title: 'USB-C Fast Charging Block (65W, Dual Port GaN)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjYWI0N2JjIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+Q2hhcmdlcjwvdGV4dD48L3N2Zz4=', recId: 'rec_afa_1' },
              { asin: 'MOCKAFA002', title: 'Universal Portable Travel Laptop Stand (Aluminum)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjMjZhNjlhIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE0IiBmaWxsPSJ3aGl0ZSI+TGFwdG9wIFN0YW5kPC90ZXh0Pjwvc3ZnPg==', recId: 'rec_afa_2' }
            ];
          } else {
            items = [
              { asin: 'MOCKAI001', title: 'Minimalist Leather Slim Card Holder Wallet', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZWM0MDdhIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+V2FsbGV0PC90ZXh0Pjwvc3ZnPg==', recId: 'rec_ai_1' },
              { asin: 'MOCKAI002', title: 'Rechargeable LED Desk Lamp with USB Port (3 Modes)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjNzg5MDljIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+TGFtcDwvdGV4dD48L3N2Zz4=', recId: 'rec_ai_2' },
              { asin: 'MOCKAI003', title: 'Stainless Steel Insulated Coffee Mug (20oz)', img: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjOGQ2ZTYzIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+TXVnPC90ZXh0Pjwvc3ZnPg==', recId: 'rec_ai_3' }
            ];
          }
          
          var grid = document.getElementById('vvp-items-grid');
          items.forEach(function(item) {
            var tile = document.createElement('div');
            tile.className = 'vvp-item-tile';
            tile.setAttribute('data-recommendation-id', item.recId);
            tile.setAttribute('data-img-url', item.img);
            
            tile.innerHTML = 
              '<img src="' + item.img + '">' +
              '<div class="vvp-item-product-title-container">' +
                '<a href="/dp/' + item.asin + '">' + item.title + '</a>' +
              '</div>' +
              '<div class="vvp-details-btn">' +
                '<input type="submit" class="a-button" data-asin="' + item.asin + '" data-recommendation-id="' + item.recId + '" value="Product Details" onclick="openMockModal(this)">' +
              '</div>';
            grid.appendChild(tile);
          });

          function openMockModal(btn) {
            var tile = btn.closest('.vvp-item-tile');
            var img = tile ? tile.querySelector('img').src : 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iMTUwIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjZmZhNzI2Ii8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGRvbWluYW50LWJhc2VsaW5lPSJtaWRkbGUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXNpemU9IjE2IiBmaWxsPSJ3aGl0ZSI+TW9jayBJdGVtPC90ZXh0Pjwvc3ZnPg==';
            var title = tile ? tile.querySelector('.vvp-item-product-title-container a').textContent.trim() : 'Mock Product Details';
            var asin = btn.getAttribute('data-asin');
            
            document.getElementById('modal-img').src = img;
            document.getElementById('modal-title').textContent = title;
            document.getElementById('modal-asin').textContent = 'ASIN: ' + asin;
            document.getElementById('popover').style.display = 'block';
            document.getElementById('scrim').style.display = 'block';
          }

          function closeModal() {
            document.getElementById('popover').style.display = 'none';
            document.getElementById('scrim').style.display = 'none';
          }

          function requestProduct() {
            alert('Product requested successfully!');
            closeModal();
          }
        </script>
        </body>
        </html>
        """.trimIndent()
    }
}
