package jyrs.dev.vivesbank.products.exceptions;

public class ProductNotFoundException extends ProductException {

    public ProductNotFoundException(Long id) {super ("Producto con id: " + id + " no encontrado");}

}
