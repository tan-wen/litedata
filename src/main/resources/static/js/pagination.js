(() => {
  'use strict';
  document.addEventListener('change', event => {
    const select = event.target;
    if (select instanceof HTMLSelectElement && select.matches('.list-pagination-size select[name="pageSize"]')) {
      select.form?.requestSubmit();
    }
  });
})();
