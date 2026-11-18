module.exports = new Proxy(Object.create(null), {
  get() { throw Error('WNFS disk operations require the Otehon host'); }
});
